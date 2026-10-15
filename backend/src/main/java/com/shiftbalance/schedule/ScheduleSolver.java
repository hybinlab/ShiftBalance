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
        List<BoolVar> allAssignmentVars = new ArrayList<>();
        Map<SlotPersonKey, List<BoolVar>> byPersonAndSlot = new HashMap<>();
        List<String> preflightIssues = new ArrayList<>();

        for (int index = 0; index < request.requirements().size(); index++) {
            StaffingRequirement requirement = request.requirements().get(index);
            List<StaffMember> eligible = eligibleStaff(request.staff(), requirement);
            if (eligible.size() < requirement.minCount()) {
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
                byPersonAndSlot.computeIfAbsent(new SlotPersonKey(requirement.slotCode(), person.id()), ignored -> new ArrayList<>())
                        .add(variable);
            }
            assignmentVars.put(index, variablesForRequirement);
            int maximum = requirement.maxCount() == null
                    ? eligible.size()
                    : Math.min(requirement.maxCount(), eligible.size());
            model.addLinearConstraint(sum(variablesForRequirement.values()), requirement.minCount(), maximum);
        }

        if (!preflightIssues.isEmpty()) {
            return new GenerateResponse(request.scheduleDate(), "INFEASIBLE", List.of(), preflightIssues, List.of());
        }

        for (List<BoolVar> variables : byPersonAndSlot.values()) {
            if (variables.size() > 1) {
                model.addAtMostOne(variables.toArray(Literal[]::new));
            }
        }

        if (allAssignmentVars.isEmpty()) {
            return new GenerateResponse(request.scheduleDate(), "OPTIMAL", List.of(), List.of(), List.of());
        }

        // ponytail: first keep assignments at configured minimums; optional overstaffing needs an explicit target policy.
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

        List<IntVar> fairnessVars = addFairnessModel(model, request, assignmentVars);
        boolean canOptimizeRepeats = true;
        if (!fairnessVars.isEmpty()) {
            model.minimize(sum(fairnessVars));
            CpSolver fairnessSolver = newSolver();
            CpSolverStatus fairnessStatus = fairnessSolver.solve(model);
            if (hasSolution(fairnessStatus)) {
                provenOptimal &= fairnessStatus == CpSolverStatus.OPTIMAL;
                selected = snapshot(fairnessSolver, assignmentVars);
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
            } else {
                provenOptimal = false;
            }
        }

        List<Assignment> assignments = toAssignments(request, selected);
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
        for (StaffingRequirement requirement : request.requirements()) {
            if (requirement == null || requirement.slotCode() == null || requirement.positionCode() == null
                    || requirement.positionName() == null || requirement.groupCode() == null) {
                throw badRequest("岗位需求字段不能为空。");
            }
            if (TimeSlotCatalog.find(requirement.slotCode()) == null) {
                throw badRequest("未知时间段：" + requirement.slotCode());
            }
            if (requirement.minCount() < 0 || requirement.maxCount() != null
                    && requirement.maxCount() < requirement.minCount()) {
                throw badRequest("岗位需求人数范围无效：" + requirement.positionCode());
            }
            String key = requirement.slotCode() + "\u0000" + requirement.positionCode();
            if (!requirementKeys.add(key)) {
                throw badRequest("同一时间段的岗位需求不能重复：" + requirement.slotCode() + " / " + requirement.positionCode());
            }
        }
    }

    private static List<StaffMember> eligibleStaff(List<StaffMember> staff, StaffingRequirement requirement) {
        return staff.stream()
                .filter(StaffMember::enabled)
                .filter(person -> person.groupCode().equals(requirement.groupCode()))
                .filter(person -> person.qualifiedPositionCodes().contains(requirement.positionCode()))
                .toList();
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
            List<StaffMember> eligible = request.staff().stream()
                    .filter(StaffMember::enabled)
                    .filter(person -> person.groupCode().equals(key.groupCode()))
                    .filter(person -> person.qualifiedPositionCodes().contains(key.positionCode()))
                    .toList();
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
            Map<Integer, Map<String, Boolean>> selected) {
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
            List<PersonSummary> people = selected.getOrDefault(index, Map.of()).entrySet().stream()
                    .filter(Map.Entry::getValue)
                    .map(entry -> peopleById.get(entry.getKey()))
                    .filter(person -> person != null)
                    .sorted(Comparator.comparing(StaffMember::name).thenComparing(StaffMember::id))
                    .map(person -> new PersonSummary(person.id(), person.name()))
                    .toList();
            assignments.add(new Assignment(slot.code(), slot.label(), slot.sequence(),
                    requirement.positionCode(), requirement.positionName(), requirement.groupCode(), people));
        }
        return assignments;
    }

    private static List<PositionFairness> fairnessSummary(GenerateRequest request, List<Assignment> assignments) {
        Map<GroupPersonPosition, Long> todayCounts = new HashMap<>();
        for (Assignment assignment : assignments) {
            for (PersonSummary person : assignment.people()) {
                todayCounts.merge(new GroupPersonPosition(assignment.groupCode(), assignment.positionCode(), person.id()),
                        1L, Long::sum);
            }
        }

        Map<GroupPosition, List<Long>> totalsByGroupPosition = new LinkedHashMap<>();
        for (StaffMember person : request.staff()) {
            if (!person.enabled()) {
                continue;
            }
            for (String positionCode : person.qualifiedPositionCodes()) {
                boolean scheduledDemand = request.requirements().stream().anyMatch(requirement ->
                        requirement.groupCode().equals(person.groupCode())
                                && requirement.positionCode().equals(positionCode));
                if (!scheduledDemand) {
                    continue;
                }
                GroupPosition key = new GroupPosition(person.groupCode(), positionCode);
                long total = person.historicalCounts().getOrDefault(positionCode, 0)
                        + todayCounts.getOrDefault(new GroupPersonPosition(key.groupCode(), key.positionCode(), person.id()), 0L);
                totalsByGroupPosition.computeIfAbsent(key, ignored -> new ArrayList<>()).add(total);
            }
        }

        return totalsByGroupPosition.entrySet().stream()
                .map(entry -> {
                    List<Long> totals = entry.getValue();
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
