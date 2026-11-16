package com.shiftbalance.requirement;

import com.shiftbalance.requirement.RequirementDtos.GroupRole;
import com.shiftbalance.requirement.RequirementDtos.RequirementTemplateUpdateRequest;
import com.shiftbalance.requirement.RequirementDtos.RequirementTemplateView;
import com.shiftbalance.requirement.RequirementDtos.RequirementView;
import com.shiftbalance.requirement.RequirementDtos.TemplateRequirementRequest;
import com.shiftbalance.schedule.SchedulingRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RequirementTemplateService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RequirementTemplateService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
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
        return new RequirementTemplateView(anchor.anchorWorkDate(), anchor.firstEarlyDownGroup(), rules(), requirements);
    }

    @Transactional
    public RequirementTemplateView save(RequirementTemplateUpdateRequest request) {
        long firstEarlyDownGroupId = enabledId("staff_group", request.firstEarlyDownGroup(), "组别");
        List<String> enabledGroups = jdbc.query("SELECT code FROM staff_group WHERE enabled ORDER BY code",
                (rs, rowNum) -> rs.getString("code"));
        if (enabledGroups.size() != 2) {
            throw badRequest("轮班模板需要恰好两个启用的组别。");
        }

        SchedulingRules rules = request.rules();
        rules.validate();
        validateRulePeople(rules, enabledGroups);
        jdbc.queryForList("SELECT singleton FROM schedule_rotation_config WHERE singleton FOR UPDATE");
        // Keep archived slot rows for historical foreign keys; active order and labels live in the snapshot.
        for (SchedulingRules.Slot slot : rules.slots()) {
            jdbc.update("""
                    INSERT INTO time_slot (code, sequence_no, label, start_time, end_time, day_offset, end_type)
                    VALUES (?, (SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM time_slot), ?, ?::time, ?::time, ?, ?)
                    ON CONFLICT (code) DO UPDATE SET label = EXCLUDED.label, start_time = EXCLUDED.start_time,
                        end_time = EXCLUDED.end_time, day_offset = EXCLUDED.day_offset, end_type = EXCLUDED.end_type
                    """, slot.code(), slot.label(), slot.start(), slot.end(), slot.dayOffset(),
                    slot.end() == null ? "FLIGHT_END" : "FIXED");
        }
        Set<String> keys = new HashSet<>();
        Set<String> fixedPeopleBySlot = new HashSet<>();
        for (TemplateRequirementRequest item : request.requirements()) {
            if (rules.slot(item.slotCode()) == null) throw badRequest("需求引用了已移除的时段：" + item.slotCode());
            if (item.positionCode().equals("LEADER")) throw badRequest("监管请在候机楼规则中配置。");
            if (item.maxCount() != null && item.maxCount() < item.minCount()) throw badRequest("最高人数不能低于最低人数。");
            if (!keys.add(item.slotCode() + ":" + item.positionCode())) throw badRequest("同一时段同一岗位不能重复。");
            enabledId("job_position", item.positionCode(), "岗位");
            if (item.fixedPersonId() != null) {
                if (item.maxCount() != null && item.maxCount() < 1) throw badRequest("固定人员需要一个岗位名额。");
                validateFixedPerson(item.fixedPersonId(), item.positionCode());
                if (!item.positionCode().equals("OVERNIGHT") && !fixedPeopleBySlot.add(item.slotCode() + ":" + item.fixedPersonId()))
                    throw badRequest("同一时段不能把同一人员固定到多个正式岗位。");
            }
        }
        for (SchedulingRules.Slot slot : rules.slots()) {
            if (!slot.remainderPosition().isEmpty() && request.requirements().stream().anyMatch(r -> r.slotCode().equals(slot.code()))
                    && request.requirements().stream().noneMatch(r -> r.slotCode().equals(slot.code()) && r.positionCode().equals(slot.remainderPosition())))
                throw badRequest(slot.label() + "：请配置接收剩余人员的岗位，或关闭自动分配。");
        }

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
        try {
            jdbc.update("UPDATE schedule_rotation_config SET rules = ?::jsonb WHERE singleton", mapper.writeValueAsString(rules));
        } catch (JsonProcessingException e) { throw new IllegalStateException("无法保存业务规则", e); }
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

        SchedulingRules rules = rules();
        if (!rules.workday(anchor.anchorWorkDate(), date)) return List.of();
        List<String> enabledGroups = jdbc.query("SELECT code FROM staff_group WHERE enabled ORDER BY code",
                (rs, rowNum) -> rs.getString("code"));
        if (enabledGroups.size() != 2) {
            throw badRequest("轮班模板需要恰好两个启用的组别。");
        }
        String firstEarlyGroup = anchor.firstEarlyDownGroup();
        String otherGroup = enabledGroups.stream().filter(code -> !code.equals(firstEarlyGroup)).findFirst()
                .orElseThrow(() -> badRequest("轮班锚点组别未启用或不在当前组别中。"));
        String earlyGroup = rules.day(anchor.anchorWorkDate(), date).earlyGroup().equals("FIRST") ? firstEarlyGroup : otherGroup;
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
        // Optional handoff slots follow the configured next-day work status.
        return resolved.stream()
                .filter(item -> rules.slot(item.slotCode()) != null)
                .filter(item -> !rules.slot(item.slotCode()).onlyBeforeWorkday() || rules.workday(anchor.anchorWorkDate(), date.plusDays(1)))
                .toList();
    }

    public LocalDate workPeriodStartDate(LocalDate date) {
        TemplateAnchor anchor = rotationAnchor();
        if (anchor == null) {
            throw notFound("尚未配置排班模板和轮班锚点。");
        }
        SchedulingRules rules = rules();
        if (!rules.workday(anchor.anchorWorkDate(), date)) throw badRequest("所选日期是休息日，请选择工作日。");
        LocalDate start = date;
        while (rules.workday(anchor.anchorWorkDate(), start.minusDays(1))) start = start.minusDays(1);
        return start;
    }

    public List<LocalDate> workPeriodDates(LocalDate date) {
        LocalDate start = workPeriodStartDate(date);
        SchedulingRules rules = rules();
        LocalDate anchor = rotationAnchor().anchorWorkDate();
        java.util.ArrayList<LocalDate> dates = new java.util.ArrayList<>();
        for (LocalDate day = start; rules.workday(anchor, day); day = day.plusDays(1)) dates.add(day);
        return dates;
    }

    public SchedulingRules rules() {
        String json = jdbc.query("SELECT COALESCE(rules::text, '') FROM schedule_rotation_config WHERE singleton",
                (rs, row) -> rs.getString(1)).stream().findFirst().orElse(null);
        try { return json == null || json.isEmpty() ? defaultRules() : mapper.readValue(json, SchedulingRules.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取业务规则", e); }
    }

    private SchedulingRules defaultRules() {
        var node = mapper.valueToTree(SchedulingRules.defaults());
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
        Map<String, List<String>> orders = new java.util.LinkedHashMap<>();
        Map<String, List<String>> names = Map.of(
                "A", List.of("唐龙", "甘春霖", "刘雨鑫", "吕珈萱", "吴世富", "石蓉昌", "骆灿", "黄勇斌", "黎哲成"),
                "B", List.of("宾昕宇", "阳菲莉", "李文锦", "王小曼", "刘芳全", "黄在显", "钟绍凌", "邹运谷", "莫少波"));
        names.forEach((group, list) -> orders.put(group, list.stream().flatMap(name -> jdbc.query(
                "SELECT id::text FROM person WHERE name = ? ORDER BY id LIMIT 1", (rs, row) -> rs.getString(1), name).stream()).toList()));
        object.set("powerBankOrder", mapper.valueToTree(orders));
        jdbc.query("SELECT id::text FROM person WHERE name = '廖家史' ORDER BY id LIMIT 1", (rs, row) -> rs.getString(1))
                .stream().findFirst().ifPresent(id -> object.put("replacementPersonId", id));
        return mapper.convertValue(object, SchedulingRules.class);
    }

    private void validateRulePeople(SchedulingRules rules, List<String> groups) {
        for (var entry : rules.powerBankOrder().entrySet()) {
            if (!groups.contains(entry.getKey())) throw badRequest("推电宝名单引用了未启用的组别。");
            for (String id : entry.getValue()) requireExists("person", "id", parseFixedPersonId(id).toString(), "人员", false);
        }
        if (rules.replacementEnabled() && rules.replacementPersonId() == null) throw badRequest("启用替岗标记时请选择被替岗人员。");
        if (rules.replacementPersonId() != null) requireExists("person", "id", parseFixedPersonId(rules.replacementPersonId()).toString(), "替岗人员", false);
        if (rules.powerBankEnabled() && groups.stream().anyMatch(group -> rules.powerBankOrder().getOrDefault(group, List.of()).isEmpty()))
            throw badRequest("启用推电宝时，每个组至少配置一名轮换人员。");
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
            jdbc.queryForObject("SELECT 1 FROM " + table + " WHERE " + column + "::text = ?" + enabledClause,
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


    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record TemplateAnchor(LocalDate anchorWorkDate, String firstEarlyDownGroup) {}
}
