package com.shiftbalance.requirement;

import com.shiftbalance.requirement.RequirementDtos.RequirementRequest;
import com.shiftbalance.requirement.RequirementDtos.RequirementView;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RequirementService {
    private final JdbcTemplate jdbc;
    private final RequirementTemplateService templateService;

    public RequirementService(JdbcTemplate jdbc, RequirementTemplateService templateService) {
        this.jdbc = jdbc;
        this.templateService = templateService;
    }

    public List<RequirementView> requirements(LocalDate date) {
        List<RequirementView> manualRequirements = jdbc.query("""
                        SELECT r.schedule_date, ts.code AS slot_code, p.code AS position_code,
                               p.name AS position_name, g.code AS group_code,
                               r.min_required_count, r.max_required_count,
                               r.fixed_person_id::text AS fixed_person_id
                        FROM daily_position_requirement r
                        JOIN time_slot ts ON ts.code = r.time_slot_code
                        JOIN job_position p ON p.id = r.position_id
                        JOIN staff_group g ON g.id = r.group_id
                        WHERE r.schedule_date = ?
                        ORDER BY ts.sequence_no, p.code
                        """,
                (rs, rowNum) -> new RequirementView(
                        rs.getDate("schedule_date").toLocalDate(),
                        rs.getString("slot_code"),
                        rs.getString("position_code"),
                        rs.getString("position_name"),
                        rs.getString("group_code"),
                        rs.getInt("min_required_count"),
                        (Integer) rs.getObject("max_required_count"),
                        rs.getString("fixed_person_id")),
                date);
        return manualRequirements.isEmpty() ? templateService.requirements(date) : manualRequirements;
    }

    @Transactional
    public List<RequirementView> save(LocalDate date, List<RequirementRequest> requests) {
        Set<String> keys = new HashSet<>();
        Set<String> fixedPeopleBySlot = new HashSet<>();
        Set<String> derivedTerminalSlots = new HashSet<>();
        Map<String, Map<String, RequirementRequest>> requirementsBySlot = new HashMap<>();
        for (RequirementRequest request : requests) {
            if (request.maxCount() != null && request.maxCount() < request.minCount()) {
                throw badRequest("最高人数不能低于最低人数：" + request.positionCode());
            }
            if (!keys.add(request.slotCode() + "\u0000" + request.positionCode())) {
                throw badRequest("同一时段的岗位需求不能重复：" + request.slotCode() + " / " + request.positionCode());
            }

            requireExists("time_slot", "code", request.slotCode(), "时间段", false);
            requireExists("job_position", "code", request.positionCode(), "岗位", true);
            requireExists("staff_group", "code", request.groupCode(), "组别", true);
            if (request.fixedPersonId() != null) {
                if (request.maxCount() != null && request.maxCount() < 1) {
                    throw badRequest("固定人员会占用一个岗位名额，最高人数不能设为 0。");
                }
                validateFixedPerson(request.fixedPersonId(), request.positionCode(), date);
                if (!fixedPeopleBySlot.add(request.slotCode() + "\u0000" + request.fixedPersonId())) {
                    throw badRequest("同一时段不能把同一人员固定到多个岗位：" + request.fixedPersonId());
                }
            }
            if ((request.positionCode().equals("BOMB") || request.positionCode().equals("MEZZANINE"))
                    && !isFixedEarlyTerminalSlot(request.slotCode())) {
                derivedTerminalSlots.add(request.slotCode());
            }
            requirementsBySlot.computeIfAbsent(request.slotCode(), ignored -> new HashMap<>())
                    .put(request.positionCode(), request);
        }
        for (RequirementRequest request : requests) {
            if (request.positionCode().equals("TERMINAL") && derivedTerminalSlots.contains(request.slotCode())
                    && (request.minCount() != 0 || request.maxCount() != null)) {
                throw badRequest("%s 的候机楼人数由其余岗位分配后自动计算，最低人数设为 0、最高人数留空。"
                        .formatted(request.slotCode()));
            }
        }
        RequirementRequest earlyMorning = requests.stream()
                .filter(item -> item.slotCode().equals("NEXT_DAY_0530_0800") && item.positionCode().equals("TERMINAL"))
                .findFirst().orElse(null);
        RequirementRequest breakfastHandoff = requests.stream()
                .filter(item -> item.slotCode().equals("NEXT_DAY_0800_0900") && item.positionCode().equals("TERMINAL"))
                .findFirst().orElse(null);
        if (earlyMorning != null && breakfastHandoff != null
                && earlyMorning.groupCode().equals(breakfastHandoff.groupCode())) {
            throw badRequest("次日 08:00–09:00 应由与 05:30–08:00 不同的组别接班。");
        }
        requirementsBySlot.forEach((slotCode, slotRequirements) -> {
            RequirementRequest bomb = slotRequirements.get("BOMB");
            RequirementRequest terminal = slotRequirements.get("TERMINAL");
            if (isFixedEarlyTerminalSlot(slotCode)) {
                RequirementRequest mezzanine = slotRequirements.get("MEZZANINE");
                RequirementRequest meal = slotRequirements.get("MEAL");
                boolean firstMorningSlot = slotCode.equals("NEXT_DAY_0530_0800");
                if (bomb == null || terminal == null || mezzanine == null
                        || firstMorningSlot && meal == null || !firstMorningSlot && meal != null
                        || slotRequirements.keySet().stream().anyMatch(position ->
                        !Set.of("BOMB", "TERMINAL", "MEZZANINE", "MEAL").contains(position))) {
                    throw badRequest("次日早班必须包含防爆、候机楼、夹层；仅 05:30–08:00 还需配置打早餐。");
                }
                if (bomb.minCount() < 3 || bomb.maxCount() != null
                        || terminal.minCount() != 1 || terminal.maxCount() == null || terminal.maxCount() != 1
                        || mezzanine.minCount() != 1 || mezzanine.maxCount() == null || mezzanine.maxCount() != 1
                        || firstMorningSlot && (meal.minCount() != 2 || meal.maxCount() == null || meal.maxCount() != 2)) {
                    throw badRequest("次日早班候机楼和夹层各 1 人；05:30–08:00 打早餐 2 人，剩余人员安排防爆（至少 3 人）。");
                }
                if (slotRequirements.values().stream().anyMatch(item -> !item.groupCode().equals(terminal.groupCode()))) {
                    throw badRequest("次日早班防爆、候机楼、夹层和打早餐必须由同一组承担。");
                }
            }
            if (derivedTerminalSlots.contains(slotCode) && terminal == null) {
                throw badRequest("%s 配置了防爆或夹层时，还必须配置候机楼岗位。".formatted(slotCode));
            }
            if (derivedTerminalSlots.contains(slotCode) && slotRequirements.values().stream()
                    .filter(item -> item.positionCode().equals("BOMB") || item.positionCode().equals("MEZZANINE"))
                    .anyMatch(item -> !item.groupCode().equals(terminal.groupCode()))) {
                throw badRequest("%s 的防爆、夹层和候机楼必须属于同一组。".formatted(slotCode));
            }
            RequirementRequest flow = slotRequirements.get("FLOW");
            if (bomb != null && flow != null) {
                String expectedFlowGroup = slotCode.equals("NIGHT_UNTIL_END") ? bomb.groupCode()
                        : slotRequirements.values().stream().map(RequirementRequest::groupCode).distinct()
                        .filter(group -> !group.equals(bomb.groupCode())).findFirst()
                        .orElseThrow(() -> badRequest("常规回流岗位需要同时指定上岗组和轮休组。"));
                if (!flow.groupCode().equals(expectedFlowGroup)) {
                    throw badRequest("%s 的回流应由%s安排。".formatted(slotCode,
                            slotCode.equals("NIGHT_UNTIL_END") ? "轮岗组" : "轮休组"));
                }
            }
        });

        jdbc.update("DELETE FROM daily_position_requirement WHERE schedule_date = ?", date);
        for (RequirementRequest request : requests) {
            jdbc.update("""
                            INSERT INTO daily_position_requirement
                                (schedule_date, position_id, time_slot_code, group_id,
                                 min_required_count, max_required_count, fixed_person_id, source)
                            VALUES (?,
                                (SELECT id FROM job_position WHERE code = ?),
                                ?,
                                (SELECT id FROM staff_group WHERE code = ?),
                                ?, ?, ?, 'MANUAL')
                            """,
                    date, request.positionCode(), request.slotCode(), request.groupCode(),
                    request.minCount(), request.maxCount(), parseFixedPersonId(request.fixedPersonId()));
        }
        return requirements(date);
    }

    @Transactional
    public List<RequirementView> clearOverride(LocalDate date) {
        jdbc.update("DELETE FROM daily_position_requirement WHERE schedule_date = ?", date);
        return requirements(date);
    }

    private void requireExists(String table, String column, String code, String label, boolean enabledOnly) {
        // ponytail: identifiers and enabledOnly come only from these internal call sites; codes are bound parameters.
        try {
            String enabledClause = enabledOnly ? " AND enabled" : "";
            jdbc.queryForObject("SELECT 1 FROM " + table + " WHERE " + column + " = ?" + enabledClause,
                    Integer.class, code);
        } catch (EmptyResultDataAccessException exception) {
            throw badRequest("未知" + label + "编码：" + code);
        }
    }

    private static boolean isFixedEarlyTerminalSlot(String slotCode) {
        return slotCode.equals("NEXT_DAY_0530_0800") || slotCode.equals("NEXT_DAY_0800_0900");
    }

    private void validateFixedPerson(String personIdText, String positionCode, LocalDate date) {
        long personId = parseFixedPersonId(personIdText);
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM person p
                        JOIN group_membership gm ON gm.person_id = p.id
                        JOIN staff_group g ON g.id = gm.group_id
                        WHERE p.id = ? AND p.enabled AND g.enabled
                          AND gm.effective_from <= ? AND (gm.effective_to IS NULL OR gm.effective_to > ?)
                          AND (? = 'TERMINAL' OR EXISTS (
                              SELECT 1 FROM person_qualification pq
                              JOIN job_position qualified_position ON qualified_position.id = pq.position_id
                              WHERE pq.person_id = p.id AND qualified_position.code = ?
                                AND qualified_position.enabled
                                AND pq.effective_from <= ? AND (pq.effective_to IS NULL OR pq.effective_to > ?)
                          ))
                        """, Long.class, personId, date, date, positionCode, positionCode, date, date);
        if (count == null || count == 0) {
            throw badRequest("固定人员不存在、未启用、所选日期不在组内或没有该岗位资格：" + personIdText);
        }
    }

    private static Long parseFixedPersonId(String personId) {
        if (personId == null) return null;
        try {
            return Long.parseLong(personId);
        } catch (NumberFormatException exception) {
            throw badRequest("固定人员 ID 必须是数据库数字 ID：" + personId);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
