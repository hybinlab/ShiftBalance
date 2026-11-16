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
import java.util.HashSet;
import java.util.List;
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
        var rules = templateService.rules();
        Set<String> keys = new HashSet<>();
        Set<String> fixed = new HashSet<>();
        for (RequirementRequest item : requests) {
            if (rules.slot(item.slotCode()) == null) throw badRequest("未知或已移除的时段：" + item.slotCode());
            if (item.positionCode().equals("LEADER")) throw badRequest("监管请在候机楼规则中配置。");
            if (item.maxCount() != null && item.maxCount() < item.minCount()) throw badRequest("最高人数不能低于最低人数。");
            if (!keys.add(item.slotCode() + ":" + item.positionCode())) throw badRequest("同一时段同一岗位不能重复。");
            requireExists("job_position", "code", item.positionCode(), "岗位", true);
            requireExists("staff_group", "code", item.groupCode(), "组别", true);
            if (item.fixedPersonId() != null) {
                if (item.maxCount() != null && item.maxCount() < 1) throw badRequest("固定人员需要一个岗位名额。");
                validateFixedPerson(item.fixedPersonId(), item.positionCode(), date);
                if (!item.positionCode().equals("OVERNIGHT") && !fixed.add(item.slotCode() + ":" + item.fixedPersonId()))
                    throw badRequest("同一时段不能把同一人员固定到多个正式岗位。");
            }
        }
        for (var slot : rules.slots()) {
            if (!slot.remainderPosition().isEmpty() && requests.stream().anyMatch(r -> r.slotCode().equals(slot.code()))
                    && requests.stream().noneMatch(r -> r.slotCode().equals(slot.code()) && r.positionCode().equals(slot.remainderPosition())))
                throw badRequest(slot.label() + "：请配置接收剩余人员的岗位，或关闭自动分配。");
        }

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
