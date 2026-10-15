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
import java.util.HashSet;
import java.util.List;
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
                               t.min_required_count, t.max_required_count
                        FROM schedule_requirement_template t
                        JOIN time_slot ts ON ts.code = t.time_slot_code
                        JOIN job_position jp ON jp.id = t.position_id
                        ORDER BY ts.sequence_no, jp.code
                        """,
                (rs, rowNum) -> new TemplateRequirementRequest(
                        rs.getString("slot_code"), rs.getString("position_code"),
                        GroupRole.valueOf(rs.getString("group_role")),
                        rs.getInt("min_required_count"), (Integer) rs.getObject("max_required_count")));
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
        for (TemplateRequirementRequest item : request.requirements()) {
            if (item.maxCount() != null && item.maxCount() < item.minCount()) {
                throw badRequest("最高人数不能低于最低人数：" + item.positionCode());
            }
            if (!keys.add(item.slotCode() + "\u0000" + item.positionCode())) {
                throw badRequest("同一时段同一岗位不能重复：" + item.slotCode() + " / " + item.positionCode());
            }
            requireExists("time_slot", "code", item.slotCode(), "时间段", false);
            enabledId("job_position", item.positionCode(), "岗位");
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
        jdbc.update("DELETE FROM schedule_requirement_template");
        for (TemplateRequirementRequest item : request.requirements()) {
            jdbc.update("""
                            INSERT INTO schedule_requirement_template
                                (time_slot_code, position_id, group_role, min_required_count, max_required_count)
                            VALUES (?, (SELECT id FROM job_position WHERE code = ?), ?, ?, ?)
                            """,
                    item.slotCode(), item.positionCode(), item.groupRole().name(),
                    item.minCount(), item.maxCount());
        }
        return template();
    }

    public List<RequirementView> requirements(LocalDate date) {
        List<TemplateAnchor> anchors = jdbc.query("""
                        SELECT c.anchor_work_date, g.code AS first_early_down_group
                        FROM schedule_rotation_config c
                        JOIN staff_group g ON g.id = c.first_early_down_group_id
                        WHERE c.singleton
                        """,
                (rs, rowNum) -> new TemplateAnchor(
                        rs.getDate("anchor_work_date").toLocalDate(), rs.getString("first_early_down_group")));
        if (anchors.isEmpty()) {
            return List.of();
        }

        long dayOffset = ChronoUnit.DAYS.between(anchors.getFirst().anchorWorkDate(), date);
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
        String firstEarlyGroup = anchors.getFirst().firstEarlyDownGroup();
        String otherGroup = enabledGroups.stream().filter(code -> !code.equals(firstEarlyGroup)).findFirst()
                .orElseThrow(() -> badRequest("轮班锚点组别未启用或不在当前组别中。"));
        String earlyGroup = rotationIndex == 0 || rotationIndex == 3 ? firstEarlyGroup : otherGroup;
        String lateGroup = earlyGroup.equals(firstEarlyGroup) ? otherGroup : firstEarlyGroup;

        List<RequirementView> resolved = jdbc.query("""
                        SELECT ts.code AS slot_code, jp.code AS position_code, jp.name AS position_name,
                               t.group_role, t.min_required_count, t.max_required_count
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
                        (Integer) rs.getObject("max_required_count")));
        // ponytail: the 08:00–09:00 handoff is needed only after workday two; keep this one known cycle exception here.
        return resolved.stream()
                .filter(item -> !item.slotCode().equals("NEXT_DAY_0800_0900") || dayInCycle == 1)
                .toList();
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

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record TemplateAnchor(LocalDate anchorWorkDate, String firstEarlyDownGroup) {}
}
