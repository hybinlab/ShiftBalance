package com.shiftbalance.schedule;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import com.shiftbalance.schedule.ScheduleDtos.Assignment;
import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateResponse;
import com.shiftbalance.schedule.ScheduleDtos.PersonSummary;
import com.shiftbalance.schedule.ScheduleDtos.PositionFairness;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ScheduleSolver {
    private static final double MAX_SOLVE_SECONDS = 15.0;
    private static final int MAX_HISTORY_COUNT = 1_000_000;

    public GenerateResponse generate(GenerateRequest request) {
        validateConfiguration(request);
        Loader.loadNativeLibraries();

        CpModel model = new CpModel();
        Map<Integer, Map<String, BoolVar>> assignmentVars = new LinkedHashMap<>();
        Map<Integer, Map<String, BoolVar>> terminalSupervisorVars;
        List<BoolVar> allAssignmentVars = new ArrayList<>();
        Map<SlotPersonKey, List<BoolVar>> byPersonAndSlot = new HashMap<>();
        List<String> preflightIssues = new ArrayList<>();

        for (int index = 0; index < request.requirements().size(); index++) {
            StaffingRequirement requirement = request.requirements().get(index);
            List<StaffMember> eligible = eligibleStaff(request.staff(), requirement, request.requirements());
            boolean derivedTerminal = isDerivedTerminal(request.requirements(), index);
            if (!derivedTerminal && eligible.size() < requirement.minCount()) {
                preflightIssues.add("%s / %s：最低需要 %d 人，符合分组和资格的启用人员只有 %d 人。"
                        .formatted(TimeSlotCatalog.find(requirement.slotCode()).label(), requirement.positionName(),
                                requirement.minCount(), eligible.size()));
                continue;
            }

            Map<String, BoolVar> variablesForRequirement = new LinkedHashMap<>();
            for (StaffMember person : eligible) {
                BoolVar variable = model.newBoolVar("assign_%d_%s".formatted(index, safeName(person.id())));
                variablesForRequirement.put(person.id(), variable);
                allAssignmentVars.add(variable);
                if (!isIndependentFromStaffing(requirement.positionCode())) {
                    byPersonAndSlot.computeIfAbsent(new SlotPersonKey(requirement.slotCode(), person.id()), ignored -> new ArrayList<>())
                            .add(variable);
                }
            }
            assignmentVars.put(index, variablesForRequirement);
            if (requirement.fixedPersonId() != null) {
                model.addEquality(variablesForRequirement.get(requirement.fixedPersonId()), 1);
            }
            if (!derivedTerminal) {
                int maximum = requirement.maxCount() == null
                        ? eligible.size()
                        : Math.min(requirement.maxCount(), eligible.size());
                model.addLinearConstraint(sum(variablesForRequirement.values()), requirement.minCount(), maximum);
            }
        }

        if (!preflightIssues.isEmpty()) {
            return new GenerateResponse(request.scheduleDate(), "INFEASIBLE", List.of(), preflightIssues, List.of());
        }

        for (List<BoolVar> variables : byPersonAndSlot.values()) {
            if (variables.size() > 1) {
                model.addAtMostOne(variables.toArray(Literal[]::new));
            }
        }
        addDerivedTerminalAssignments(model, request, assignmentVars);
        addDerivedEarlyBombAssignments(model, request, assignmentVars);
        addFlowNoRepeat(model, request.requirements(), assignmentVars);
        terminalSupervisorVars = addTerminalSupervisorModel(model, request.requirements(), assignmentVars);

        if (allAssignmentVars.isEmpty()) {
            return new GenerateResponse(request.scheduleDate(), "OPTIMAL", List.of(), List.of(), List.of());
        }

        // ponytail: terminal coverage assigns everyone not needed on fixed posts, so total assignments alone cannot
        // discourage optional staffing above a post's minimum; minimize that overage before fairness optimization.
        model.minimize(sum(allAssignmentVars));
        CpSolver solver = newSolver();
        CpSolverStatus status = solver.solve(model);
        if (!hasSolution(status)) {
            String message = status == CpSolverStatus.INFEASIBLE
                    ? "岗位需求之间存在冲突：即使各岗位候选人数足够，同一人员在同一时段也不能兼任多个岗位。"
                    : "求解器未能在时限内找到可行排班。";
            return new GenerateResponse(request.scheduleDate(), status.name(), List.of(), List.of(message), List.of());
        }

        boolean provenOptimal = status == CpSolverStatus.OPTIMAL;
        long minimumAssignmentCount = selectedCount(solver, allAssignmentVars);
        model.addEquality(sum(allAssignmentVars), minimumAssignmentCount);
        Map<Integer, Map<String, Boolean>> selected = snapshot(solver, assignmentVars);
        Map<Integer, Set<String>> selectedSupervisors = snapshotSupervisors(solver, terminalSupervisorVars);

        List<IntVar> overstaffingVars = addOverstaffingModel(model, request.requirements(), assignmentVars);
        boolean canOptimizeRepeats = true;
        if (!overstaffingVars.isEmpty()) {
            model.minimize(sum(overstaffingVars));
            CpSolver overstaffingSolver = newSolver();
            CpSolverStatus overstaffingStatus = overstaffingSolver.solve(model);
            if (hasSolution(overstaffingStatus)) {
                provenOptimal &= overstaffingStatus == CpSolverStatus.OPTIMAL;
                selected = snapshot(overstaffingSolver, assignmentVars);
                selectedSupervisors = snapshotSupervisors(overstaffingSolver, terminalSupervisorVars);
                long overstaffingCost = selectedCount(overstaffingSolver, overstaffingVars);
                model.addEquality(sum(overstaffingVars), overstaffingCost);
            } else {
                provenOptimal = false;
                canOptimizeRepeats = false;
            }
        }

        List<IntVar> fairnessVars = addFairnessModel(model, request, assignmentVars);
        if (!fairnessVars.isEmpty()) {
            model.minimize(sum(fairnessVars));
            CpSolver fairnessSolver = newSolver();
            CpSolverStatus fairnessStatus = fairnessSolver.solve(model);
            if (hasSolution(fairnessStatus)) {
                provenOptimal &= fairnessStatus == CpSolverStatus.OPTIMAL;
                selected = snapshot(fairnessSolver, assignmentVars);
                selectedSupervisors = snapshotSupervisors(fairnessSolver, terminalSupervisorVars);
                long fairnessCost = selectedCount(fairnessSolver, fairnessVars);
                model.addEquality(sum(fairnessVars), fairnessCost);
            } else {
                provenOptimal = false;
                canOptimizeRepeats = false;
            }
        }

        List<BoolVar> repeatVars = canOptimizeRepeats
                ? addSamePositionRepeatModel(model, request.requirements(), assignmentVars)
                : List.of();
        if (!repeatVars.isEmpty()) {
            model.minimize(sum(repeatVars));
            CpSolver repeatSolver = newSolver();
            CpSolverStatus repeatStatus = repeatSolver.solve(model);
            if (hasSolution(repeatStatus)) {
                provenOptimal &= repeatStatus == CpSolverStatus.OPTIMAL;
                selected = snapshot(repeatSolver, assignmentVars);
                selectedSupervisors = snapshotSupervisors(repeatSolver, terminalSupervisorVars);
            } else {
                provenOptimal = false;
            }
        }

        List<BoolVar> preferencePenalties = addRolePreferenceModel(model, request, assignmentVars);
        if (!preferencePenalties.isEmpty()) {
            model.minimize(sum(preferencePenalties));
            CpSolver preferenceSolver = newSolver();
            CpSolverStatus preferenceStatus = preferenceSolver.solve(model);
            if (hasSolution(preferenceStatus)) {
                provenOptimal &= preferenceStatus == CpSolverStatus.OPTIMAL;
                selected = snapshot(preferenceSolver, assignmentVars);
                selectedSupervisors = snapshotSupervisors(preferenceSolver, terminalSupervisorVars);
            } else {
                provenOptimal = false;
            }
        }

        List<Assignment> assignments = toAssignments(request, selected, selectedSupervisors);
        List<PositionFairness> fairness = fairnessSummary(request, assignments);
        return new GenerateResponse(
                request.scheduleDate(),
                provenOptimal ? "OPTIMAL" : "FEASIBLE",
                assignments,
                provenOptimal ? List.of() : List.of("已返回可行方案，但求解器未证明所有优化阶段均达到最优。"),
                fairness);
    }

    private static void validateConfiguration(GenerateRequest request) {
        if (request == null || request.scheduleDate() == null || request.staff() == null || request.requirements() == null) {
            throw badRequest("排班日期、人员和岗位需求不能为空。");
        }

        Set<String> personIds = new LinkedHashSet<>();
        for (StaffMember person : request.staff()) {
            if (person == null || person.id() == null || person.id().isBlank() || person.name() == null
                    || person.name().isBlank() || person.groupCode() == null || person.groupCode().isBlank()) {
                throw badRequest("人员 ID、姓名和分组不能为空。");
            }
            if (!personIds.add(person.id())) {
                throw badRequest("人员 ID 重复：" + person.id());
            }
            if (person.qualifiedPositionCodes() == null || person.historicalCounts() == null) {
                throw badRequest("人员资格和历史岗位次数不能为空；没有数据时请传空集合或空对象。");
            }
            for (Map.Entry<String, Integer> entry : person.historicalCounts().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                        || entry.getValue() < 0 || entry.getValue() > MAX_HISTORY_COUNT) {
                    throw badRequest("历史岗位次数必须是 0 到 %d 之间的整数。".formatted(MAX_HISTORY_COUNT));
                }
            }
        }

        Set<String> requirementKeys = new LinkedHashSet<>();
        Map<SlotPersonKey, Set<String>> fixedPositionsBySlot = new HashMap<>();
        for (StaffingRequirement requirement : request.requirements()) {
            if (requirement == null || requirement.slotCode() == null || requirement.positionCode() == null
                    || requirement.positionName() == null || requirement.groupCode() == null) {
                throw badRequest("岗位需求字段不能为空。");
            }
            if (TimeSlotCatalog.find(requirement.slotCode()) == null) {
                throw badRequest("未知时间段：" + requirement.slotCode());
            }
            if (requirement.positionCode().equals("LEADER")) {
                throw badRequest("负责人不是独立岗位；请配置候机楼岗位，负责人会从候机楼人员中标记。");
            }
            if (requirement.positionCode().equals("BOMB") && requirement.minCount() < 3) {
                throw badRequest("防爆岗位每段至少安排 3 人。");
            }
            if (requirement.positionCode().equals("MEAL")
                    && (requirement.minCount() != 2 || requirement.maxCount() != null && requirement.maxCount() != 2)) {
                throw badRequest("每次打饭必须安排 2 人。");
            }
            if (requirement.minCount() < 0 || requirement.maxCount() != null
                    && requirement.maxCount() < requirement.minCount()) {
                throw badRequest("岗位需求人数范围无效：" + requirement.positionCode());
            }
            if (requirement.fixedPersonId() != null) {
                StaffMember fixedPerson = request.staff().stream()
                        .filter(person -> person.id().equals(requirement.fixedPersonId()))
                        .findFirst().orElse(null);
                if (fixedPerson == null || !fixedPerson.enabled()
                        || !requirement.positionCode().equals("TERMINAL")
                        && !fixedPerson.qualifiedPositionCodes().contains(requirement.positionCode())) {
                    throw badRequest("固定人员不在所选日期启用人员中或没有该岗位资格：" + requirement.fixedPersonId());
                }
                if (requirement.maxCount() != null && requirement.maxCount() < 1) {
                    throw badRequest("固定人员会占用一个岗位名额，最高人数不能设为 0。");
                }
                SlotPersonKey fixedKey = new SlotPersonKey(requirement.slotCode(), requirement.fixedPersonId());
                Set<String> fixedPositions = fixedPositionsBySlot.computeIfAbsent(fixedKey, ignored -> new LinkedHashSet<>());
                if (!isIndependentFromStaffing(requirement.positionCode())
                        && fixedPositions.stream().anyMatch(position -> !isIndependentFromStaffing(position))) {
                    throw badRequest("同一时段不能把同一人员固定到多个岗位：" + requirement.fixedPersonId());
                }
                fixedPositions.add(requirement.positionCode());
            }
            String key = requirement.slotCode() + "\u0000" + requirement.positionCode();
            if (!requirementKeys.add(key)) {
                throw badRequest("同一时间段的岗位需求不能重复：" + requirement.slotCode() + " / " + requirement.positionCode());
            }
        }

        Map<String, List<StaffingRequirement>> requirementsBySlot = new LinkedHashMap<>();
        request.requirements().forEach(requirement -> requirementsBySlot
                .computeIfAbsent(requirement.slotCode(), ignored -> new ArrayList<>()).add(requirement));
        for (Map.Entry<String, List<StaffingRequirement>> slot : requirementsBySlot.entrySet()) {
            List<StaffingRequirement> requirements = slot.getValue();
            StaffingRequirement bomb = requirement(requirements, "BOMB");
            StaffingRequirement terminal = requirement(requirements, "TERMINAL");
            boolean derivesTerminal = !isFixedEarlyTerminalSlot(slot.getKey()) && requirements.stream().anyMatch(item ->
                    item.positionCode().equals("BOMB") || item.positionCode().equals("MEZZANINE"));
            if (derivesTerminal && terminal == null) {
                throw badRequest("%s 配置了防爆或夹层时，还必须配置候机楼岗位；候机楼人员由剩余人员自动计算。"
                        .formatted(slot.getKey()));
            }
            if (isFixedEarlyTerminalSlot(slot.getKey())
                    && (terminal == null || terminal.minCount() != 1 || terminal.maxCount() == null || terminal.maxCount() != 1)) {
                throw badRequest("次日早班候机楼（负责人）必须安排 1 人。");
            }
            if (isFixedEarlyTerminalSlot(slot.getKey())) {
                StaffingRequirement mezzanine = requirements.stream()
                        .filter(item -> item.positionCode().equals("MEZZANINE")).findFirst().orElse(null);
                StaffingRequirement meal = requirements.stream()
                        .filter(item -> item.positionCode().equals("MEAL")).findFirst().orElse(null);
                boolean firstMorningSlot = slot.getKey().equals("NEXT_DAY_0530_0800");
                if (bomb == null || mezzanine == null || firstMorningSlot && meal == null
                        || !firstMorningSlot && meal != null
                        || requirements.stream().anyMatch(item ->
                        !Set.of("BOMB", "TERMINAL", "MEZZANINE", "MEAL").contains(item.positionCode()))) {
                    throw badRequest("次日早班必须包含防爆、候机楼、夹层；仅 05:30–08:00 还需打早餐。");
                }
                if (bomb.minCount() < 3 || bomb.maxCount() != null
                        || mezzanine.minCount() != 1 || mezzanine.maxCount() == null || mezzanine.maxCount() != 1
                        || firstMorningSlot && (meal.minCount() != 2 || meal.maxCount() == null || meal.maxCount() != 2)) {
                    throw badRequest("次日早班候机楼和夹层各 1 人；05:30–08:00 打早餐 2 人，剩余人员安排防爆（至少 3 人）。");
                }
                if (requirements.stream().filter(item -> item.positionCode().equals("BOMB")
                                || item.positionCode().equals("TERMINAL")
                                || item.positionCode().equals("MEZZANINE")
                                || item.positionCode().equals("MEAL"))
                        .anyMatch(item -> !item.groupCode().equals(terminal.groupCode()))) {
                    throw badRequest("次日早班防爆、候机楼、夹层和打早餐必须由同一组承担。");
                }
            }
            if (derivesTerminal && requirements.stream()
                    .filter(item -> item.positionCode().equals("BOMB") || item.positionCode().equals("MEZZANINE"))
                    .anyMatch(item -> !item.groupCode().equals(terminal.groupCode()))) {
                throw badRequest("%s 的防爆、夹层和候机楼必须属于同一组。".formatted(slot.getKey()));
            }
            StaffingRequirement flow = requirement(requirements, "FLOW");
            if (bomb != null && flow != null) {
                String expectedFlowGroup;
                if (slot.getKey().equals("NIGHT_UNTIL_END")) {
                    expectedFlowGroup = bomb.groupCode();
                } else {
                    expectedFlowGroup = request.staff().stream().map(StaffMember::groupCode).distinct()
                            .filter(group -> !group.equals(bomb.groupCode())).findFirst()
                            .orElseThrow(() -> badRequest("常规回流岗位需要同时提供上岗组和轮休组人员。"));
                }
                if (!flow.groupCode().equals(expectedFlowGroup)) {
                    throw badRequest("%s 的回流应由%s安排。".formatted(slot.getKey(),
                            slot.getKey().equals("NIGHT_UNTIL_END") ? "轮岗组" : "轮休组"));
                }
            }
        }
    }

    private static List<StaffMember> eligibleStaff(
            List<StaffMember> staff,
            StaffingRequirement requirement,
            List<StaffingRequirement> allRequirements) {
        return staff.stream()
                .filter(StaffMember::enabled)
                .filter(person -> {
                    boolean fixedAnywhere = allRequirements.stream()
                            .anyMatch(item -> person.id().equals(item.fixedPersonId()));
                    return fixedAnywhere
                            ? person.id().equals(requirement.fixedPersonId())
                            || person.groupCode().equals(requirement.groupCode())
                            && allRequirements.stream().anyMatch(item -> person.id().equals(item.fixedPersonId())
                            && item.slotCode().equals(requirement.slotCode())
                            && (isIndependentFromStaffing(item.positionCode())
                            || isIndependentFromStaffing(requirement.positionCode())))
                            : person.groupCode().equals(requirement.groupCode());
                })
                .filter(person -> requirement.positionCode().equals("TERMINAL")
                        || person.qualifiedPositionCodes().contains(requirement.positionCode()))
                .toList();
    }

    private static boolean isFixedOutsideGroupSlot(
            GenerateRequest request, String slotCode, String groupCode, StaffMember person) {
        boolean fixedAnywhere = request.requirements().stream()
                .anyMatch(requirement -> person.id().equals(requirement.fixedPersonId()));
        return fixedAnywhere && request.requirements().stream().noneMatch(requirement ->
                requirement.slotCode().equals(slotCode) && person.id().equals(requirement.fixedPersonId())
                        && (requirement.groupCode().equals(groupCode)
                        || isIndependentFromStaffing(requirement.positionCode())));
    }

    private static StaffingRequirement requirement(List<StaffingRequirement> requirements, String positionCode) {
        return requirements.stream().filter(item -> item.positionCode().equals(positionCode)).findFirst().orElse(null);
    }

    private static boolean isDerivedTerminal(List<StaffingRequirement> requirements, int index) {
        StaffingRequirement terminal = requirements.get(index);
        return terminal.positionCode().equals("TERMINAL") && !isFixedEarlyTerminalSlot(terminal.slotCode())
                && requirements.stream().anyMatch(item ->
                item.slotCode().equals(terminal.slotCode())
                        && (item.positionCode().equals("BOMB") || item.positionCode().equals("MEZZANINE")));
    }

    private static boolean isDerivedEarlyBomb(List<StaffingRequirement> requirements, int index) {
        StaffingRequirement bomb = requirements.get(index);
        return bomb.positionCode().equals("BOMB") && isFixedEarlyTerminalSlot(bomb.slotCode());
    }

    private static boolean isFixedEarlyTerminalSlot(String slotCode) {
        return slotCode.equals("NEXT_DAY_0530_0800") || slotCode.equals("NEXT_DAY_0800_0900");
    }

    private static boolean isIndependentFromStaffing(String positionCode) {
        return positionCode.equals("OVERNIGHT");
    }

    private static List<IntVar> addFairnessModel(
            CpModel model,
            GenerateRequest request,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        Map<GroupPosition, List<Integer>> requirementsByGroupPosition = new LinkedHashMap<>();
        for (int i = 0; i < request.requirements().size(); i++) {
            StaffingRequirement requirement = request.requirements().get(i);
            requirementsByGroupPosition.computeIfAbsent(
                    new GroupPosition(requirement.groupCode(), requirement.positionCode()), ignored -> new ArrayList<>())
                    .add(i);
        }

        List<IntVar> differences = new ArrayList<>();
        for (Map.Entry<GroupPosition, List<Integer>> entry : requirementsByGroupPosition.entrySet()) {
            GroupPosition key = entry.getKey();
            List<StaffMember> eligible = entry.getValue().stream()
                    .flatMap(index -> eligibleStaff(request.staff(), request.requirements().get(index),
                            request.requirements()).stream())
                    .collect(java.util.stream.Collectors.toMap(StaffMember::id, person -> person,
                            (first, ignored) -> first, LinkedHashMap::new))
                    .values().stream().toList();
            if (eligible.size() < 2) {
                continue;
            }

            Map<String, IntVar> projectedCounts = new LinkedHashMap<>();
            for (StaffMember person : eligible) {
                long history = person.historicalCounts().getOrDefault(key.positionCode(), 0);
                LinearExprBuilder countExpression = LinearExpr.newBuilder().add(history);
                int possibleAssignments = 0;
                for (int requirementIndex : entry.getValue()) {
                    BoolVar variable = assignmentVars.getOrDefault(requirementIndex, Map.of()).get(person.id());
                    if (variable != null) {
                        countExpression.addTerm(variable, 1);
                        possibleAssignments++;
                    }
                }
                IntVar projected = model.newIntVar(history, history + possibleAssignments,
                        "projected_%s_%s".formatted(safeName(key.positionCode()), safeName(person.id())));
                model.addEquality(projected, countExpression.build());
                projectedCounts.put(person.id(), projected);
            }

            for (int left = 0; left < eligible.size(); left++) {
                for (int right = left + 1; right < eligible.size(); right++) {
                    IntVar leftCount = projectedCounts.get(eligible.get(left).id());
                    IntVar rightCount = projectedCounts.get(eligible.get(right).id());
                    long maxDifference = Math.max(leftCount.getDomain().max(), rightCount.getDomain().max());
                    IntVar difference = model.newIntVar(0, maxDifference,
                            "fairness_%s_%d_%d".formatted(safeName(key.positionCode()), left, right));
                    LinearExpr differenceExpression = LinearExpr.newBuilder()
                            .addTerm(leftCount, 1)
                            .addTerm(rightCount, -1)
                            .build();
                    model.addAbsEquality(difference, differenceExpression);
                    differences.add(difference);
                }
            }
        }
        return differences;
    }

    private static List<IntVar> addOverstaffingModel(
            CpModel model,
            List<StaffingRequirement> requirements,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        List<IntVar> overstaffing = new ArrayList<>();
        for (int i = 0; i < requirements.size(); i++) {
            StaffingRequirement requirement = requirements.get(i);
            if (requirement.maxCount() != null || isDerivedTerminal(requirements, i)
                    || isDerivedEarlyBomb(requirements, i)) {
                continue;
            }
            Map<String, BoolVar> variables = assignmentVars.getOrDefault(i, Map.of());
            int upperBound = Math.max(0, variables.size() - requirement.minCount());
            if (upperBound == 0) {
                continue;
            }
            LinearExprBuilder excessExpression = LinearExpr.newBuilder().add(-requirement.minCount());
            variables.values().forEach(variable -> excessExpression.addTerm(variable, 1));
            IntVar excess = model.newIntVar(0, upperBound, "overstaff_%d".formatted(i));
            model.addEquality(excess, excessExpression.build());
            overstaffing.add(excess);
        }
        return overstaffing;
    }

    private static void addDerivedEarlyBombAssignments(
            CpModel model,
            GenerateRequest request,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        for (int bombIndex = 0; bombIndex < request.requirements().size(); bombIndex++) {
            if (!isDerivedEarlyBomb(request.requirements(), bombIndex)) {
                continue;
            }
            StaffingRequirement bomb = request.requirements().get(bombIndex);
            for (StaffMember person : request.staff()) {
                if (!person.enabled() || !person.groupCode().equals(bomb.groupCode())
                        || isFixedOutsideGroupSlot(request, bomb.slotCode(), bomb.groupCode(), person)) {
                    continue;
                }
                List<BoolVar> sameSlotAssignments = new ArrayList<>();
                for (int index = 0; index < request.requirements().size(); index++) {
                    StaffingRequirement requirement = request.requirements().get(index);
                    if (!requirement.slotCode().equals(bomb.slotCode())
                            || !requirement.groupCode().equals(bomb.groupCode())
                            || isIndependentFromStaffing(requirement.positionCode())) {
                        continue;
                    }
                    BoolVar variable = assignmentVars.getOrDefault(index, Map.of()).get(person.id());
                    if (variable != null) {
                        sameSlotAssignments.add(variable);
                    }
                }
                model.addEquality(sum(sameSlotAssignments), 1);
            }
        }
    }

    private static void addFlowNoRepeat(
            CpModel model,
            List<StaffingRequirement> requirements,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        Map<String, List<BoolVar>> byPerson = new LinkedHashMap<>();
        for (int i = 0; i < requirements.size(); i++) {
            if (!requirements.get(i).positionCode().equals("FLOW")) {
                continue;
            }
            assignmentVars.getOrDefault(i, Map.of()).forEach((personId, variable) ->
                    byPerson.computeIfAbsent(personId, ignored -> new ArrayList<>()).add(variable));
        }
        byPerson.values().stream().filter(variables -> variables.size() > 1)
                .forEach(variables -> model.addAtMostOne(variables.toArray(Literal[]::new)));
    }

    private static void addDerivedTerminalAssignments(
            CpModel model,
            GenerateRequest request,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        for (int terminalIndex = 0; terminalIndex < request.requirements().size(); terminalIndex++) {
            if (!isDerivedTerminal(request.requirements(), terminalIndex)) {
                continue;
            }
            StaffingRequirement terminal = request.requirements().get(terminalIndex);
            for (StaffMember person : request.staff()) {
                if (!person.enabled() || !person.groupCode().equals(terminal.groupCode())
                        || isFixedOutsideGroupSlot(request, terminal.slotCode(), terminal.groupCode(), person)) {
                    continue;
                }
                List<BoolVar> sameSlotAssignments = new ArrayList<>();
                BoolVar terminalAssignment = assignmentVars.getOrDefault(terminalIndex, Map.of()).get(person.id());
                if (terminalAssignment != null) {
                    sameSlotAssignments.add(terminalAssignment);
                }
                for (int index = 0; index < request.requirements().size(); index++) {
                    StaffingRequirement other = request.requirements().get(index);
                    if (index == terminalIndex || !other.slotCode().equals(terminal.slotCode())
                            || !other.groupCode().equals(terminal.groupCode())
                            || isIndependentFromStaffing(other.positionCode())) {
                        continue;
                    }
                    BoolVar variable = assignmentVars.getOrDefault(index, Map.of()).get(person.id());
                    if (variable != null) {
                        sameSlotAssignments.add(variable);
                    }
                }
                model.addEquality(sum(sameSlotAssignments), 1);
            }
        }
    }

    private static Map<Integer, Map<String, BoolVar>> addTerminalSupervisorModel(
            CpModel model,
            List<StaffingRequirement> requirements,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        Map<Integer, Map<String, BoolVar>> supervisorVars = new LinkedHashMap<>();
        Map<String, List<BoolVar>> byPerson = new LinkedHashMap<>();
        for (int i = 0; i < requirements.size(); i++) {
            StaffingRequirement requirement = requirements.get(i);
            if (!requirement.positionCode().equals("TERMINAL")) {
                continue;
            }
            Map<String, BoolVar> choices = new LinkedHashMap<>();
            for (Map.Entry<String, BoolVar> entry : assignmentVars.getOrDefault(i, Map.of()).entrySet()) {
                BoolVar choice = model.newBoolVar("terminal_supervisor_%d_%s".formatted(i, safeName(entry.getKey())));
                model.addLessOrEqual(choice, entry.getValue());
                choices.put(entry.getKey(), choice);
                byPerson.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(choice);
            }
            model.addEquality(sum(choices.values()), 1);
            supervisorVars.put(i, choices);
        }
        byPerson.values().stream().filter(choices -> choices.size() > 1)
                .forEach(choices -> model.addAtMostOne(choices.toArray(Literal[]::new)));
        return supervisorVars;
    }

    private static List<BoolVar> addRolePreferenceModel(
            CpModel model,
            GenerateRequest request,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        Map<String, StaffMember> peopleById = new HashMap<>();
        request.staff().forEach(person -> peopleById.put(person.id(), person));
        List<BoolVar> penalties = new ArrayList<>();
        for (int i = 0; i < request.requirements().size(); i++) {
            String positionCode = request.requirements().get(i).positionCode();
            Map<String, BoolVar> variables = assignmentVars.getOrDefault(i, Map.of());
            if (positionCode.equals("MEAL")) {
                variables.forEach((personId, variable) -> {
                    StaffMember person = peopleById.get(personId);
                    if (person != null && !person.qualifiedPositionCodes().contains("BOMB")) {
                        penalties.add(variable);
                    }
                });
            } else if (positionCode.equals("FLOW")) {
                int flowIndex = i;
                String slotCode = request.requirements().get(i).slotCode();
                variables.forEach((personId, flowVariable) -> {
                    List<BoolVar> otherTerminalAssignments = new ArrayList<>();
                    for (int terminalIndex = 0; terminalIndex < request.requirements().size(); terminalIndex++) {
                        StaffingRequirement terminal = request.requirements().get(terminalIndex);
                        BoolVar terminalVariable = assignmentVars.getOrDefault(terminalIndex, Map.of()).get(personId);
                        if (terminal.positionCode().equals("TERMINAL") && !terminal.slotCode().equals(slotCode)
                                && TimeSlotCatalog.find(terminal.slotCode()).dayOffset() == 0
                                && terminalVariable != null) {
                            otherTerminalAssignments.add(terminalVariable);
                        }
                    }
                    if (otherTerminalAssignments.isEmpty()) {
                        penalties.add(flowVariable);
                        return;
                    }
                    BoolVar terminalElsewhere = model.newBoolVar("terminal_elsewhere_%s_%d"
                            .formatted(safeName(personId), flowIndex));
                    otherTerminalAssignments.forEach(variable -> model.addGreaterOrEqual(terminalElsewhere, variable));
                    model.addLessOrEqual(terminalElsewhere, sum(otherTerminalAssignments));
                    BoolVar penalty = model.newBoolVar("flow_without_terminal_%s_%d"
                            .formatted(safeName(personId), flowIndex));
                    LinearExpr penaltyLowerBound = LinearExpr.newBuilder()
                            .addTerm(flowVariable, 1).addTerm(terminalElsewhere, -1).build();
                    model.addGreaterOrEqual(penalty, penaltyLowerBound);
                    model.addLessOrEqual(penalty, flowVariable);
                    model.addLessOrEqual(LinearExpr.newBuilder()
                            .addTerm(penalty, 1).addTerm(terminalElsewhere, 1).build(), 1);
                    penalties.add(penalty);
                });
            }
        }
        return penalties;
    }

    private static List<BoolVar> addSamePositionRepeatModel(
            CpModel model,
            List<StaffingRequirement> requirements,
            Map<Integer, Map<String, BoolVar>> assignmentVars) {
        Map<GroupPosition, Map<String, List<BoolVar>>> byGroupPositionAndPerson = new LinkedHashMap<>();
        for (int i = 0; i < requirements.size(); i++) {
            StaffingRequirement requirement = requirements.get(i);
            GroupPosition key = new GroupPosition(requirement.groupCode(), requirement.positionCode());
            for (Map.Entry<String, BoolVar> entry : assignmentVars.getOrDefault(i, Map.of()).entrySet()) {
                byGroupPositionAndPerson.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(entry.getValue());
            }
        }

        List<BoolVar> repeats = new ArrayList<>();
        for (Map<String, List<BoolVar>> people : byGroupPositionAndPerson.values()) {
            for (List<BoolVar> variables : people.values()) {
                for (int left = 0; left < variables.size(); left++) {
                    for (int right = left + 1; right < variables.size(); right++) {
                        BoolVar repeat = model.newBoolVar("repeat_%d".formatted(repeats.size()));
                        BoolVar first = variables.get(left);
                        BoolVar second = variables.get(right);
                        model.addLessOrEqual(repeat, first);
                        model.addLessOrEqual(repeat, second);
                        LinearExpr bothAssigned = LinearExpr.newBuilder()
                                .addTerm(first, 1)
                                .addTerm(second, 1)
                                .add(-1)
                                .build();
                        model.addGreaterOrEqual(repeat, bothAssigned);
                        repeats.add(repeat);
                    }
                }
            }
        }
        return repeats;
    }

    private static List<Assignment> toAssignments(
            GenerateRequest request,
            Map<Integer, Map<String, Boolean>> selected,
            Map<Integer, Set<String>> selectedSupervisors) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < request.requirements().size(); i++) {
            indexes.add(i);
        }
        indexes.sort(Comparator.comparingInt(i -> TimeSlotCatalog.find(request.requirements().get(i).slotCode()).sequence()));

        Map<String, StaffMember> peopleById = new HashMap<>();
        request.staff().forEach(person -> peopleById.put(person.id(), person));
        List<Assignment> assignments = new ArrayList<>();
        for (int index : indexes) {
            StaffingRequirement requirement = request.requirements().get(index);
            TimeSlotCatalog.TimeSlot slot = TimeSlotCatalog.find(requirement.slotCode());
            Set<String> supervisorIds = selectedSupervisors.getOrDefault(index, Set.of());
            List<PersonSummary> people = selected.getOrDefault(index, Map.of()).entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(entry -> peopleById.get(entry.getKey()))
                    .filter(person -> person != null)
                    .sorted(Comparator.comparing(StaffMember::name).thenComparing(StaffMember::id))
                    .map(person -> new PersonSummary(person.id(), person.name(), supervisorIds.contains(person.id())))
                    .toList();
            assignments.add(new Assignment(slot.code(), slot.label(), slot.sequence(),
                    requirement.positionCode(), requirement.positionName(), requirement.groupCode(),
                    requirement.fixedPersonId(), people));
        }
        return assignments;
    }

    private static Map<Integer, Set<String>> snapshotSupervisors(
            CpSolver solver,
            Map<Integer, Map<String, BoolVar>> variables) {
        Map<Integer, Set<String>> selected = new LinkedHashMap<>();
        variables.forEach((index, choices) -> {
            Set<String> people = new LinkedHashSet<>();
            choices.forEach((personId, variable) -> {
                if (solver.booleanValue(variable)) {
                    people.add(personId);
                }
            });
            selected.put(index, Set.copyOf(people));
        });
        return selected;
    }

    private static List<PositionFairness> fairnessSummary(GenerateRequest request, List<Assignment> assignments) {
        Map<GroupPersonPosition, Long> todayCounts = new HashMap<>();
        for (Assignment assignment : assignments) {
            for (PersonSummary person : assignment.people()) {
                todayCounts.merge(new GroupPersonPosition(assignment.groupCode(), assignment.positionCode(), person.id()),
                        1L, Long::sum);
            }
        }

        Map<GroupPosition, Map<String, Long>> totalsByGroupPosition = new LinkedHashMap<>();
        for (StaffingRequirement requirement : request.requirements()) {
            GroupPosition key = new GroupPosition(requirement.groupCode(), requirement.positionCode());
            for (StaffMember person : eligibleStaff(request.staff(), requirement, request.requirements())) {
                long total = person.historicalCounts().getOrDefault(requirement.positionCode(), 0)
                        + todayCounts.getOrDefault(
                        new GroupPersonPosition(key.groupCode(), key.positionCode(), person.id()), 0L);
                totalsByGroupPosition.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                        .put(person.id(), total);
            }
        }

        return totalsByGroupPosition.entrySet().stream()
                .map(entry -> {
                    List<Long> totals = List.copyOf(entry.getValue().values());
                    return new PositionFairness(entry.getKey().groupCode(), entry.getKey().positionCode(),
                            Math.toIntExact(totals.stream().mapToLong(Long::longValue).min().orElse(0)),
                            Math.toIntExact(totals.stream().mapToLong(Long::longValue).max().orElse(0)));
                })
                .toList();
    }

    private static CpSolver newSolver() {
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(MAX_SOLVE_SECONDS);
        return solver;
    }

    private static boolean hasSolution(CpSolverStatus status) {
        return status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
    }

    private static long selectedCount(CpSolver solver, List<? extends LinearArgument> variables) {
        long count = 0;
        for (LinearArgument variable : variables) {
            count += Math.round(solver.value(variable));
        }
        return count;
    }

    private static Map<Integer, Map<String, Boolean>> snapshot(
            CpSolver solver,
            Map<Integer, Map<String, BoolVar>> variables) {
        Map<Integer, Map<String, Boolean>> selected = new LinkedHashMap<>();
        variables.forEach((index, people) -> {
            Map<String, Boolean> selectedPeople = new LinkedHashMap<>();
            people.forEach((personId, variable) -> selectedPeople.put(personId, solver.booleanValue(variable)));
            selected.put(index, selectedPeople);
        });
        return selected;
    }

    private static LinearExpr sum(Iterable<? extends LinearArgument> variables) {
        LinearExprBuilder expression = LinearExpr.newBuilder();
        variables.forEach(variable -> expression.addTerm(variable, 1));
        return expression.build();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private record SlotPersonKey(String slotCode, String personId) {}

    private record GroupPosition(String groupCode, String positionCode) {}

    private record GroupPersonPosition(String groupCode, String positionCode, String personId) {}
}
