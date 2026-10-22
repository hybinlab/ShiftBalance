package com.shiftbalance.requirement;

import com.shiftbalance.requirement.RequirementDtos.GroupRole;
import com.shiftbalance.requirement.RequirementDtos.RequirementTemplateUpdateRequest;
import com.shiftbalance.requirement.RequirementDtos.RequirementTemplateView;
import com.shiftbalance.requirement.RequirementDtos.RequirementView;
import com.shiftbalance.requirement.RequirementDtos.TemplateRequirementRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RequirementTemplateService {
    private final JdbcTemplate jdbc;

    public RequirementTemplateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RequirementTemplateView template() {
        TemplateAnchor anchor = jdbc.query("""
                        SELECT c.anchor_work_date, g.code AS first_early_down_group
                        FROM schedule_rotation_config c
                        JOIN staff_group g ON g.id = c.first_early_down_group_id
                        WHERE c.singleton
                        """,
                (rs, rowNum) -> new TemplateAnchor(
                        rs.getDate("anchor_work_date").toLocalDate(), rs.getString("first_early_down_group")))
                .stream().findFirst().orElseThrow(() -> notFound("尚未配置排班模板和轮班锚点。"));
        List<TemplateRequirementRequest> requirements = jdbc.query("""
                        SELECT ts.code AS slot_code, jp.code AS position_code, t.group_role,
                               t.min_required_count, t.max_required_count, t.fixed_person_id::text AS fixed_person_id
                        FROM schedule_requirement_template t
                        JOIN time_slot ts ON ts.code = t.time_slot_code
                        JOIN job_position jp ON jp.id = t.position_id
                        ORDER BY ts.sequence_no, jp.code
                        """,
                (rs, rowNum) -> new TemplateRequirementRequest(
                        rs.getString("slot_code"), rs.getString("position_code"),
                        GroupRole.valueOf(rs.getString("group_role")),
                        rs.getInt("min_required_count"), (Integer) rs.getObject("max_required_count"),
                        rs.getString("fixed_person_id")));
        return new RequirementTemplateView(anchor.anchorWorkDate(), anchor.firstEarlyDownGroup(), requirements);
    }

    @Transactional
    public RequirementTemplateView save(RequirementTemplateUpdateRequest request) {
        long firstEarlyDownGroupId = enabledId("staff_group", request.firstEarlyDownGroup(), "组别");
        List<String> enabledGroups = jdbc.query("SELECT code FROM staff_group WHERE enabled ORDER BY code",
                (rs, rowNum) -> rs.getString("code"));
        if (enabledGroups.size() != 2) {
            throw badRequest("轮班模板需要恰好两个启用的组别。");
        }

        Set<String> keys = new HashSet<>();
        Set<String> fixedPeopleBySlot = new HashSet<>();
        Set<String> derivedTerminalSlots = new HashSet<>();
        Map<String, Map<String, GroupRole>> rolesBySlot = new HashMap<>();
        Map<String, Map<String, TemplateRequirementRequest>> requirementsBySlot = new HashMap<>();
        for (TemplateRequirementRequest item : request.requirements()) {
            if (item.maxCount() != null && item.maxCount() < item.minCount()) {
                throw badRequest("最高人数不能低于最低人数：" + item.positionCode());
            }
            if (item.slotCode().equals("NEXT_DAY_0800_0900")
                    && item.groupRole() != GroupRole.LATE_DOWN) {
                throw badRequest("次日 08:00–09:00 由另一组上岗，用餐切换时段必须设置为 LATE_DOWN。");
            }
            if (item.slotCode().equals("NEXT_DAY_0530_0800") && item.groupRole() != GroupRole.EARLY_DOWN) {
                throw badRequest("次日 05:30–08:00 由早下组上岗，必须设置为 EARLY_DOWN。");
            }
            if (!keys.add(item.slotCode() + "\u0000" + item.positionCode())) {
                throw badRequest("同一时段同一岗位不能重复：" + item.slotCode() + " / " + item.positionCode());
            }
            requireExists("time_slot", "code", item.slotCode(), "时间段", false);
            enabledId("job_position", item.positionCode(), "岗位");
            if (item.fixedPersonId() != null) {
                if (item.maxCount() != null && item.maxCount() < 1) {
                    throw badRequest("固定人员会占用一个岗位名额，最高人数不能设为 0。");
                }
                validateFixedPerson(item.fixedPersonId(), item.positionCode());
                if (!fixedPeopleBySlot.add(item.slotCode() + "\u0000" + item.fixedPersonId())) {
                    throw badRequest("同一时段不能把同一人员固定到多个岗位：" + item.fixedPersonId());
                }
            }
            if ((item.positionCode().equals("BOMB") || item.positionCode().equals("MEZZANINE"))
                    && !isFixedEarlyTerminalSlot(item.slotCode())) {
                derivedTerminalSlots.add(item.slotCode());
            }
            rolesBySlot.computeIfAbsent(item.slotCode(), ignored -> new HashMap<>())
                    .put(item.positionCode(), item.groupRole());
            requirementsBySlot.computeIfAbsent(item.slotCode(), ignored -> new HashMap<>())
                    .put(item.positionCode(), item);
        }
        for (TemplateRequirementRequest item : request.requirements()) {
            if (item.positionCode().equals("TERMINAL") && derivedTerminalSlots.contains(item.slotCode())
                    && (item.minCount() != 0 || item.maxCount() != null)) {
                throw badRequest("%s 的候机楼人数由其余岗位分配后自动计算，最低人数设为 0、最高人数留空。"
                        .formatted(item.slotCode()));
            }
        }
        rolesBySlot.forEach((slotCode, roles) -> {
            GroupRole bombRole = roles.get("BOMB");
            GroupRole flowRole = roles.get("FLOW");
            GroupRole terminalRole = roles.get("TERMINAL");
            GroupRole mezzanineRole = roles.get("MEZZANINE");
            if ((bombRole != null || mezzanineRole != null) && terminalRole == null) {
                throw badRequest("%s 配置了防爆或夹层时，还必须配置候机楼岗位。".formatted(slotCode));
            }
            if (terminalRole != null && (bombRole != null && terminalRole != bombRole
                    || mezzanineRole != null && terminalRole != mezzanineRole)) {
                throw badRequest("%s 的防爆、夹层和候机楼必须属于同一组。".formatted(slotCode));
            }
            if (bombRole != null && flowRole != null) {
                GroupRole expectedFlowRole = slotCode.equals("NIGHT_UNTIL_END")
                        ? bombRole
                        : bombRole == GroupRole.EARLY_DOWN ? GroupRole.LATE_DOWN : GroupRole.EARLY_DOWN;
                if (flowRole != expectedFlowRole) {
                    throw badRequest("%s 的回流组别应根据上岗组自动交替：常规时段取轮休组，19:30 后取轮岗组。"
                            .formatted(slotCode));
                }
            }
            if (isFixedEarlyTerminalSlot(slotCode)) {
                TemplateRequirementRequest bomb = requirementsBySlot.get(slotCode).get("BOMB");
                TemplateRequirementRequest terminal = requirementsBySlot.get(slotCode).get("TERMINAL");
                TemplateRequirementRequest mezzanine = requirementsBySlot.get(slotCode).get("MEZZANINE");
                TemplateRequirementRequest meal = requirementsBySlot.get(slotCode).get("MEAL");
                boolean firstMorningSlot = slotCode.equals("NEXT_DAY_0530_0800");
                GroupRole expectedRole = firstMorningSlot ? GroupRole.EARLY_DOWN : GroupRole.LATE_DOWN;
                if (bomb == null || terminal == null || mezzanine == null
                        || firstMorningSlot && meal == null || !firstMorningSlot && meal != null
                        || roles.keySet().stream().anyMatch(position ->
                        !Set.of("BOMB", "TERMINAL", "MEZZANINE", "MEAL").contains(position))) {
                    throw badRequest("次日早班必须包含防爆、候机楼、夹层；仅 05:30–08:00 还需配置打早餐。");
                }
                if (bomb.minCount() < 3 || bomb.maxCount() != null
                        || terminal.minCount() != 1 || terminal.maxCount() == null || terminal.maxCount() != 1
                        || mezzanine.minCount() != 1 || mezzanine.maxCount() == null || mezzanine.maxCount() != 1
                        || firstMorningSlot && (meal.minCount() != 2 || meal.maxCount() == null || meal.maxCount() != 2)) {
                    throw badRequest("次日早班候机楼和夹层各 1 人；05:30–08:00 打早餐 2 人，剩余人员安排防爆（至少 3 人）。");
                }
                if (roles.entrySet().stream().anyMatch(entry ->
                        Set.of("BOMB", "TERMINAL", "MEZZANINE", "MEAL").contains(entry.getKey())
                                && entry.getValue() != expectedRole)) {
                    throw badRequest("次日早班的岗位必须由轮班规则指定的上岗组承担。");
                }
            }
        });

        jdbc.update("""
                        INSERT INTO schedule_rotation_config
                            (singleton, anchor_work_date, first_early_down_group_id)
                        VALUES (TRUE, ?, ?)
                        ON CONFLICT (singleton) DO UPDATE SET
                            anchor_work_date = EXCLUDED.anchor_work_date,
                            first_early_down_group_id = EXCLUDED.first_early_down_group_id,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                request.anchorWorkDate(), firstEarlyDownGroupId);
        jdbc.update("DELETE FROM schedule_requirement_template");
        for (TemplateRequirementRequest item : request.requirements()) {
            jdbc.update("""
                            INSERT INTO schedule_requirement_template
                                (time_slot_code, position_id, group_role, min_required_count, max_required_count,
                                 fixed_person_id)
                            VALUES (?, (SELECT id FROM job_position WHERE code = ?), ?, ?, ?, ?)
                            """,
                    item.slotCode(), item.positionCode(), item.groupRole().name(),
                    item.minCount(), item.maxCount(), parseFixedPersonId(item.fixedPersonId()));
        }
        return template();
    }

    public List<RequirementView> requirements(LocalDate date) {
        TemplateAnchor anchor = rotationAnchor();
        if (anchor == null) {
            return List.of();
        }

        long dayOffset = ChronoUnit.DAYS.between(anchor.anchorWorkDate(), date);
        // ponytail: derive the 2-on/2-off cycle from one anchor; replace this fixed cycle with a work calendar if exceptions are introduced.
        int dayInCycle = (int) Math.floorMod(dayOffset, 4);
        if (dayInCycle >= 2) {
            return List.of();
        }
        long workdayIndex = Math.floorDiv(dayOffset, 4) * 2 + dayInCycle;
        long rotationIndex = Math.floorMod(workdayIndex, 4);

        List<String> enabledGroups = jdbc.query("SELECT code FROM staff_group WHERE enabled ORDER BY code",
                (rs, rowNum) -> rs.getString("code"));
        if (enabledGroups.size() != 2) {
            throw badRequest("轮班模板需要恰好两个启用的组别。");
        }
        String firstEarlyGroup = anchor.firstEarlyDownGroup();
        String otherGroup = enabledGroups.stream().filter(code -> !code.equals(firstEarlyGroup)).findFirst()
                .orElseThrow(() -> badRequest("轮班锚点组别未启用或不在当前组别中。"));
        String earlyGroup = rotationIndex == 0 || rotationIndex == 3 ? firstEarlyGroup : otherGroup;
        String lateGroup = earlyGroup.equals(firstEarlyGroup) ? otherGroup : firstEarlyGroup;

        List<RequirementView> resolved = jdbc.query("""
                        SELECT ts.code AS slot_code, jp.code AS position_code, jp.name AS position_name,
                               t.group_role, t.min_required_count, t.max_required_count,
                               t.fixed_person_id::text AS fixed_person_id
                        FROM schedule_requirement_template t
                        JOIN time_slot ts ON ts.code = t.time_slot_code
                        JOIN job_position jp ON jp.id = t.position_id
                        ORDER BY ts.sequence_no, jp.code
                        """,
                (rs, rowNum) -> new RequirementView(
                        date,
                        rs.getString("slot_code"),
                        rs.getString("position_code"),
                        rs.getString("position_name"),
                        GroupRole.valueOf(rs.getString("group_role")) == GroupRole.EARLY_DOWN ? earlyGroup : lateGroup,
                        rs.getInt("min_required_count"),
                        (Integer) rs.getObject("max_required_count"),
                        rs.getString("fixed_person_id")));
        // ponytail: the opposite group takes over 08:00–09:00 only after the first workday.
        return resolved.stream()
                .filter(item -> !item.slotCode().equals("NEXT_DAY_0800_0900") || dayInCycle == 0)
                .toList();
    }

    public LocalDate workPeriodStartDate(LocalDate date) {
        TemplateAnchor anchor = rotationAnchor();
        if (anchor == null) {
            throw notFound("尚未配置排班模板和轮班锚点。");
        }
        int dayInCycle = (int) Math.floorMod(ChronoUnit.DAYS.between(anchor.anchorWorkDate(), date), 4);
        if (dayInCycle >= 2) {
            throw badRequest("所选日期是休息日，请选择上班日生成两天排班。");
        }
        return date.minusDays(dayInCycle);
    }

    private TemplateAnchor rotationAnchor() {
        return jdbc.query("""
                        SELECT c.anchor_work_date, g.code AS first_early_down_group
                        FROM schedule_rotation_config c
                        JOIN staff_group g ON g.id = c.first_early_down_group_id
                        WHERE c.singleton
                        """,
                (rs, rowNum) -> new TemplateAnchor(
                        rs.getDate("anchor_work_date").toLocalDate(), rs.getString("first_early_down_group")))
                .stream().findFirst().orElse(null);
    }

    private long enabledId(String table, String code, String label) {
        try {
            Long id = jdbc.queryForObject("SELECT id FROM " + table + " WHERE code = ? AND enabled", Long.class, code);
            if (id == null) {
                throw badRequest("未知或停用的" + label + "编码：" + code);
            }
            return id;
        } catch (EmptyResultDataAccessException exception) {
            throw badRequest("未知或停用的" + label + "编码：" + code);
        }
    }

    private void requireExists(String table, String column, String code, String label, boolean enabledOnly) {
        try {
            String enabledClause = enabledOnly ? " AND enabled" : "";
            jdbc.queryForObject("SELECT 1 FROM " + table + " WHERE " + column + " = ?" + enabledClause,
                    Integer.class, code);
        } catch (EmptyResultDataAccessException exception) {
            throw badRequest("未知" + label + "编码：" + code);
        }
    }

    private void validateFixedPerson(String personIdText, String positionCode) {
        long personId = parseFixedPersonId(personIdText);
        LocalDate today = LocalDate.now();
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
                        """, Long.class, personId, today, today, positionCode, positionCode, today, today);
        if (count == null || count == 0) {
            throw badRequest("固定人员不存在、未启用、当前不在组内或没有该岗位资格：" + personIdText);
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

    private static boolean isFixedEarlyTerminalSlot(String slotCode) {
        return slotCode.equals("NEXT_DAY_0530_0800") || slotCode.equals("NEXT_DAY_0800_0900");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record TemplateAnchor(LocalDate anchorWorkDate, String firstEarlyDownGroup) {}
}
