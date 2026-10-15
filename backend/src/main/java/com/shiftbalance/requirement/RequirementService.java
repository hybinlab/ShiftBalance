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
                               r.min_required_count, r.max_required_count
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
                        (Integer) rs.getObject("max_required_count")),
                date);
        return manualRequirements.isEmpty() ? templateService.requirements(date) : manualRequirements;
    }

    @Transactional
    public List<RequirementView> save(LocalDate date, List<RequirementRequest> requests) {
        Set<String> keys = new HashSet<>();
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
        }

        jdbc.update("DELETE FROM daily_position_requirement WHERE schedule_date = ?", date);
        for (RequirementRequest request : requests) {
            jdbc.update("""
                            INSERT INTO daily_position_requirement
                                (schedule_date, position_id, time_slot_code, group_id,
                                 min_required_count, max_required_count, source)
                            VALUES (?,
                                (SELECT id FROM job_position WHERE code = ?),
                                ?,
                                (SELECT id FROM staff_group WHERE code = ?),
                                ?, ?, 'MANUAL')
                            """,
                    date, request.positionCode(), request.slotCode(), request.groupCode(),
                    request.minCount(), request.maxCount());
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

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
