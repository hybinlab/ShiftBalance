package com.shiftbalance.statistics;

import com.shiftbalance.statistics.StatisticsDtos.PersonPositionAssignment;
import com.shiftbalance.statistics.StatisticsDtos.PersonPositionAssignmentsResponse;
import com.shiftbalance.statistics.StatisticsDtos.PositionDistribution;
import com.shiftbalance.statistics.StatisticsDtos.PositionDistributionResponse;
import com.shiftbalance.statistics.StatisticsDtos.ScheduleStatistics;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsService {
    private static final String HISTORICAL_SCHEDULE_PREDICATE = """
            s.status = 'PUBLISHED'
            AND s.version = (SELECT MAX(latest.version) FROM schedule latest
                             WHERE latest.schedule_date = s.schedule_date AND latest.status = 'PUBLISHED')
            """;
    private static final String ASSIGNMENT_SQL = """
            WITH scoped_schedules AS (
                SELECT s.id, s.schedule_date, s.configuration_snapshot
                FROM schedule s
                WHERE %s
            ), required_slots AS (
                SELECT ss.id AS schedule_id, ss.schedule_date, ts.code AS slot_code,
                       position.id AS position_id, position.code AS position_code,
                       position.name AS position_name, staff_group.id AS group_id,
                       staff_group.code AS group_code
                FROM scoped_schedules ss
                CROSS JOIN LATERAL jsonb_to_recordset(
                    CASE WHEN jsonb_typeof(ss.configuration_snapshot->'requirements') = 'array'
                         THEN ss.configuration_snapshot->'requirements' ELSE '[]'::jsonb END
                ) AS requirement("slotCode" TEXT, "positionCode" TEXT, "groupCode" TEXT,
                                 "minCount" INTEGER, "maxCount" INTEGER)
                JOIN time_slot ts ON ts.code = requirement."slotCode"
                JOIN job_position position ON position.code = requirement."positionCode"
                JOIN staff_group ON staff_group.code = requirement."groupCode"
                WHERE COALESCE(requirement."minCount", 0) > 0
                   OR requirement."maxCount" IS NULL
                   OR requirement."maxCount" > 0
                   OR position.code = 'TERMINAL'
            ), eligible_slots AS (
                SELECT DISTINCT required.schedule_id, required.schedule_date, required.slot_code,
                       required.position_id, required.position_code, required.position_name,
                       required.group_id, required.group_code, person.id AS person_id
                FROM required_slots required
                JOIN group_membership membership ON membership.group_id = required.group_id
                    AND membership.effective_from <= required.schedule_date
                    AND (membership.effective_to IS NULL OR membership.effective_to > required.schedule_date)
                JOIN person ON person.id = membership.person_id
                WHERE required.position_code = 'TERMINAL' OR EXISTS (
                    SELECT 1
                    FROM person_qualification qualification
                    WHERE qualification.person_id = person.id
                      AND qualification.position_id = required.position_id
                      AND qualification.effective_from <= required.schedule_date
                      AND (qualification.effective_to IS NULL OR qualification.effective_to > required.schedule_date)
                )
            ), opportunity_counts AS (
                SELECT person_id, position_id, group_id,
                       COUNT(DISTINCT (schedule_id, slot_code))::BIGINT AS eligible_slot_count
                FROM eligible_slots
                GROUP BY person_id, position_id, group_id
            ), assignment_counts AS (
                SELECT assignment.person_id, assignment.position_id,
                       assignment.group_id_snapshot AS group_id,
                       COUNT(DISTINCT assignment.id)::BIGINT AS assignment_count
                FROM scoped_schedules scoped
                JOIN schedule_assignment assignment ON assignment.schedule_id = scoped.id
                GROUP BY assignment.person_id, assignment.position_id, assignment.group_id_snapshot
            ), fairness_counts AS (
                SELECT assignment.person_id, assignment.position_id,
                       assignment.group_id_snapshot AS group_id,
                       COUNT(DISTINCT assignment.id)::BIGINT AS fairness_count
                FROM scoped_schedules scoped
                JOIN schedule_assignment assignment ON assignment.schedule_id = scoped.id
                JOIN job_position position ON position.id = assignment.position_id
                JOIN staff_group ON staff_group.id = assignment.group_id_snapshot
                JOIN required_slots required ON required.schedule_id = scoped.id
                    AND required.slot_code = assignment.time_slot_code
                    AND required.position_id = assignment.position_id
                    AND required.group_id = assignment.group_id_snapshot
                WHERE EXISTS (
                    SELECT 1 FROM group_membership membership
                    WHERE membership.person_id = assignment.person_id
                      AND membership.group_id = assignment.group_id_snapshot
                      AND membership.effective_from <= scoped.schedule_date
                      AND (membership.effective_to IS NULL OR membership.effective_to > scoped.schedule_date)
                )
                  AND (position.code = 'TERMINAL' OR EXISTS (
                      SELECT 1 FROM person_qualification qualification
                      WHERE qualification.person_id = assignment.person_id
                        AND qualification.position_id = assignment.position_id
                        AND qualification.effective_from <= scoped.schedule_date
                        AND (qualification.effective_to IS NULL OR qualification.effective_to > scoped.schedule_date)
                  ))
                GROUP BY assignment.person_id, assignment.position_id, assignment.group_id_snapshot
            ), cohort AS (
                SELECT person_id, position_id, group_id FROM opportunity_counts
                UNION
                SELECT person_id, position_id, group_id FROM assignment_counts
            )
            SELECT person.id AS person_id, person.name AS person_name,
                   position.id AS position_id, position.code AS position_code, position.name AS position_name,
                   staff_group.id AS group_id, staff_group.code AS group_code,
                   COALESCE(assignment_count.assignment_count, 0)::BIGINT AS assignment_count,
                   COALESCE(fairness_count.fairness_count, 0)::BIGINT AS fairness_count,
                   COALESCE(opportunity.eligible_slot_count, 0)::BIGINT AS eligible_slot_count
            FROM cohort
            JOIN person ON person.id = cohort.person_id
            JOIN job_position position ON position.id = cohort.position_id
            JOIN staff_group ON staff_group.id = cohort.group_id
            LEFT JOIN assignment_counts assignment_count ON assignment_count.person_id = cohort.person_id
                AND assignment_count.position_id = cohort.position_id AND assignment_count.group_id = cohort.group_id
            LEFT JOIN fairness_counts fairness_count ON fairness_count.person_id = cohort.person_id
                AND fairness_count.position_id = cohort.position_id AND fairness_count.group_id = cohort.group_id
            LEFT JOIN opportunity_counts opportunity ON opportunity.person_id = cohort.person_id
                AND opportunity.position_id = cohort.position_id AND opportunity.group_id = cohort.group_id
            WHERE TRUE %s
            ORDER BY staff_group.code, position.code, person.name, person.id
            """;

    private final JdbcTemplate jdbc;

    public StatisticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PersonPositionAssignmentsResponse personPositionAssignments(
            LocalDate startDate, LocalDate endDate, Long personId, Long positionId, Long groupId) {
        validateFilters(startDate, endDate, personId, positionId, groupId);
        List<PersonPositionAssignment> rows = loadAssignments(HISTORICAL_SCHEDULE_PREDICATE, List.of(),
                startDate, endDate, personId, positionId, groupId);
        return new PersonPositionAssignmentsResponse(startDate, endDate, rows);
    }

    public PositionDistributionResponse positionDistribution(
            LocalDate startDate, LocalDate endDate, Long positionId, Long groupId) {
        validateFilters(startDate, endDate, null, positionId, groupId);
        List<PersonPositionAssignment> rows = loadAssignments(HISTORICAL_SCHEDULE_PREDICATE, List.of(),
                startDate, endDate, null, positionId, groupId);
        return new PositionDistributionResponse(startDate, endDate, distributions(rows));
    }

    public ScheduleStatistics scheduleStatistics(long scheduleId) {
        if (scheduleId <= 0) throw badRequest("scheduleId 必须为正整数。");
        List<ScheduleHeader> headers = jdbc.query("SELECT schedule_date, status FROM schedule WHERE id = ?",
                (rs, rowNum) -> new ScheduleHeader(rs.getObject("schedule_date", LocalDate.class), rs.getString("status")),
                scheduleId);
        if (headers.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "排班不存在：" + scheduleId);
        ScheduleHeader header = headers.getFirst();
        if (!header.status().equals("CANDIDATE") && !header.status().equals("PUBLISHED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待发布或已发布排班可查询单次分配核对。");
        }
        List<PersonPositionAssignment> rows = loadAssignments(
                "s.id = ? AND s.status IN ('CANDIDATE', 'PUBLISHED')", List.<Object>of(scheduleId),
                null, null, null, null, null);
        return new ScheduleStatistics(scheduleId, header.scheduleDate(), header.status(),
                rows.stream().mapToLong(PersonPositionAssignment::assignmentCount).sum(),
                rows, distributions(rows));
    }

    private List<PersonPositionAssignment> loadAssignments(
            String schedulePredicate, List<Object> scheduleParameters,
            LocalDate startDate, LocalDate endDate,
            Long personId, Long positionId, Long groupId) {
        StringBuilder scheduleWhere = new StringBuilder(schedulePredicate);
        List<Object> parameters = new ArrayList<>(scheduleParameters);
        if (startDate != null) {
            scheduleWhere.append(" AND s.schedule_date >= ?");
            parameters.add(startDate);
        }
        if (endDate != null) {
            scheduleWhere.append(" AND s.schedule_date <= ?");
            parameters.add(endDate);
        }

        StringBuilder rowFilters = new StringBuilder();
        if (personId != null) {
            rowFilters.append(" AND cohort.person_id = ?");
            parameters.add(personId);
        }
        if (positionId != null) {
            rowFilters.append(" AND cohort.position_id = ?");
            parameters.add(positionId);
        }
        if (groupId != null) {
            rowFilters.append(" AND cohort.group_id = ?");
            parameters.add(groupId);
        }

        return jdbc.query(ASSIGNMENT_SQL.formatted(scheduleWhere, rowFilters),
                (rs, rowNum) -> new PersonPositionAssignment(
                        rs.getLong("person_id"), rs.getString("person_name"),
                        rs.getLong("position_id"), rs.getString("position_code"), rs.getString("position_name"),
                        rs.getLong("group_id"), rs.getString("group_code"),
                        rs.getLong("assignment_count"), rs.getLong("fairness_count"), rs.getLong("eligible_slot_count")),
                parameters.toArray());
    }

    private static List<PositionDistribution> distributions(List<PersonPositionAssignment> rows) {
        Map<GroupPosition, List<PersonPositionAssignment>> grouped = new LinkedHashMap<>();
        for (PersonPositionAssignment row : rows) {
            GroupPosition key = new GroupPosition(row.positionId(), row.positionCode(), row.positionName(),
                    row.groupId(), row.groupCode());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream().map(entry -> {
            List<PersonPositionAssignment> eligible = entry.getValue().stream()
                    .filter(row -> row.eligibleSlotCount() > 0).toList();
            long min = eligible.stream().mapToLong(PersonPositionAssignment::fairnessCount).min().orElse(0);
            long max = eligible.stream().mapToLong(PersonPositionAssignment::fairnessCount).max().orElse(0);
            return new PositionDistribution(entry.getKey().positionId(), entry.getKey().positionCode(),
                    entry.getKey().positionName(), entry.getKey().groupId(), entry.getKey().groupCode(),
                    eligible.size(), eligible.stream().filter(row -> row.assignmentCount() > 0).count(),
                    entry.getValue().stream().mapToLong(PersonPositionAssignment::assignmentCount).sum(),
                    eligible.stream().mapToLong(PersonPositionAssignment::fairnessCount).sum(),
                    min, max, max - min,
                    eligible.isEmpty() ? 0 : (double) eligible.stream().mapToLong(PersonPositionAssignment::fairnessCount).sum() / eligible.size());
        }).toList();
    }

    private static void validateFilters(LocalDate startDate, LocalDate endDate,
                                        Long personId, Long positionId, Long groupId) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw badRequest("startDate 不能晚于 endDate。");
        }
        if (personId != null && personId <= 0) throw badRequest("personId 必须为正整数。");
        if (positionId != null && positionId <= 0) throw badRequest("positionId 必须为正整数。");
        if (groupId != null && groupId <= 0) throw badRequest("groupId 必须为正整数。");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record ScheduleHeader(LocalDate scheduleDate, String status) {}
    private record GroupPosition(long positionId, String positionCode, String positionName,
                                 long groupId, String groupCode) {}
}
