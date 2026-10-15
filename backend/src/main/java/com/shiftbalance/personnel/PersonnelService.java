package com.shiftbalance.personnel;

import com.shiftbalance.personnel.PersonnelDtos.CreatePersonRequest;
import com.shiftbalance.personnel.PersonnelDtos.GroupView;
import com.shiftbalance.personnel.PersonnelDtos.PersonView;
import com.shiftbalance.personnel.PersonnelDtos.PositionView;
import com.shiftbalance.personnel.PersonnelDtos.UpdatePersonRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PersonnelService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;

    public PersonnelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<GroupView> groups() {
        return jdbc.query("SELECT code, name FROM staff_group WHERE enabled ORDER BY code",
                (rs, rowNum) -> new GroupView(rs.getString("code"), rs.getString("name")));
    }

    public List<PositionView> positions() {
        return jdbc.query("SELECT code, name FROM job_position WHERE enabled ORDER BY code",
                (rs, rowNum) -> new PositionView(rs.getString("code"), rs.getString("name")));
    }

    public List<PersonView> people(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now(BUSINESS_ZONE) : asOf;
        List<PersonRow> people = jdbc.query("""
                        SELECT p.id, p.name, p.enabled, p.remark, g.code AS group_code
                        FROM person p
                        JOIN group_membership gm ON gm.person_id = p.id
                        JOIN staff_group g ON g.id = gm.group_id
                        WHERE gm.effective_from <= ? AND (gm.effective_to IS NULL OR gm.effective_to > ?)
                        ORDER BY p.id
                        """,
                (rs, rowNum) -> new PersonRow(rs), date, date);
        if (people.isEmpty()) {
            return List.of();
        }

        List<Long> ids = people.stream().map(PersonRow::id).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] idsAndDate = append(ids, date, date);

        Map<Long, Set<String>> qualifications = new HashMap<>();
        jdbc.query("""
                        SELECT pq.person_id, jp.code
                        FROM person_qualification pq
                        JOIN job_position jp ON jp.id = pq.position_id
                        WHERE pq.person_id IN (%s)
                          AND pq.effective_from <= ? AND (pq.effective_to IS NULL OR pq.effective_to > ?)
                        ORDER BY pq.person_id, jp.code
                        """.formatted(placeholders),
                (RowCallbackHandler) rs -> qualifications.computeIfAbsent(rs.getLong("person_id"), ignored -> new LinkedHashSet<>())
                        .add(rs.getString("code")), idsAndDate);

        Map<Long, Map<String, Integer>> historicalCounts = new HashMap<>();
        jdbc.query("""
                        SELECT sa.person_id, jp.code, COUNT(*) AS assignment_count
                        FROM schedule_assignment sa
                        JOIN schedule s ON s.id = sa.schedule_id AND s.status = 'PUBLISHED'
                        JOIN job_position jp ON jp.id = sa.position_id
                        WHERE sa.person_id IN (%s)
                        GROUP BY sa.person_id, jp.code
                        ORDER BY sa.person_id, jp.code
                        """.formatted(placeholders),
                (RowCallbackHandler) rs -> historicalCounts.computeIfAbsent(rs.getLong("person_id"), ignored -> new LinkedHashMap<>())
                        .put(rs.getString("code"), Math.toIntExact(rs.getLong("assignment_count"))), ids.toArray());

        return people.stream().map(person -> new PersonView(
                        Long.toString(person.id()),
                        person.name(),
                        person.groupCode(),
                        person.enabled(),
                        person.remark(),
                        List.copyOf(qualifications.getOrDefault(person.id(), Set.of())),
                        Map.copyOf(historicalCounts.getOrDefault(person.id(), Map.of()))))
                .toList();
    }

    @Transactional
    public PersonView createPerson(CreatePersonRequest request) {
        long groupId = lookupEnabledId("staff_group", request.groupCode(), "组别");
        Map<String, Long> positionIds = new LinkedHashMap<>();
        for (String code : request.qualifiedPositionCodes()) {
            positionIds.put(code, lookupEnabledId("job_position", code, "岗位"));
        }

        Long personId = jdbc.queryForObject("""
                        INSERT INTO person (name, enabled, remark)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                Long.class, request.name().trim(), request.enabled(), request.remark());
        if (personId == null) {
            throw new IllegalStateException("数据库没有返回新人员 ID。");
        }

        jdbc.update("""
                        INSERT INTO group_membership (person_id, group_id, effective_from)
                        VALUES (?, ?, ?)
                        """,
                personId, groupId, request.effectiveFrom());
        for (long positionId : positionIds.values()) {
            jdbc.update("""
                            INSERT INTO person_qualification (person_id, position_id, effective_from)
                            VALUES (?, ?, ?)
                            """,
                    personId, positionId, request.effectiveFrom());
        }

        return people(request.effectiveFrom()).stream()
                .filter(person -> person.id().equals(Long.toString(personId)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("新建人员未能读取。"));
    }

    @Transactional
    public PersonView updatePerson(long id, UpdatePersonRequest request, LocalDate asOf) {
        int updated = jdbc.update("""
                        UPDATE person
                        SET name = ?, enabled = ?, remark = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                request.name().trim(), request.enabled(), request.remark(), id);
        if (updated == 0) {
            throw notFound("人员不存在：" + id);
        }
        LocalDate effectiveDate = LocalDate.now(BUSINESS_ZONE);
        if (request.groupCode() != null) {
            updateGroupMembership(id, request.groupCode(), effectiveDate);
        }
        if (request.qualifiedPositionCodes() != null) {
            updateQualifications(id, request.qualifiedPositionCodes(), effectiveDate);
        }
        return people(asOf).stream()
                .filter(person -> person.id().equals(Long.toString(id)))
                .findFirst()
                .orElseThrow(() -> notFound("该日期没有人员分组记录：" + id));
    }

    private void updateGroupMembership(long personId, String groupCode, LocalDate effectiveDate) {
        long desiredGroupId = lookupEnabledId("staff_group", groupCode, "组别");
        List<GroupMembershipRow> activeMemberships = jdbc.query("""
                        SELECT id, group_id, effective_from
                        FROM group_membership
                        WHERE person_id = ? AND effective_from <= ?
                          AND (effective_to IS NULL OR effective_to > ?)
                        """,
                (rs, rowNum) -> new GroupMembershipRow(rs.getLong("id"), rs.getLong("group_id"),
                        rs.getObject("effective_from", LocalDate.class)),
                personId, effectiveDate, effectiveDate);
        if (activeMemberships.isEmpty()) {
            throw notFound("该日期没有人员分组记录：" + personId);
        }
        if (activeMemberships.size() != 1) {
            throw new IllegalStateException("人员在同一日期存在多条有效组别记录：" + personId);
        }

        GroupMembershipRow active = activeMemberships.getFirst();
        if (active.groupId() == desiredGroupId) {
            return;
        }
        if (active.effectiveFrom().equals(effectiveDate)) {
            jdbc.update("UPDATE group_membership SET group_id = ? WHERE id = ?", desiredGroupId, active.id());
        } else {
            jdbc.update("UPDATE group_membership SET effective_to = ? WHERE id = ?", effectiveDate, active.id());
            jdbc.update("""
                            INSERT INTO group_membership (person_id, group_id, effective_from)
                            VALUES (?, ?, ?)
                            """,
                    personId, desiredGroupId, effectiveDate);
        }
    }

    private void updateQualifications(long personId, Set<String> positionCodes, LocalDate effectiveDate) {
        Set<Long> desiredPositionIds = new LinkedHashSet<>();
        for (String code : positionCodes) {
            desiredPositionIds.add(lookupEnabledId("job_position", code, "岗位"));
        }

        List<QualificationRow> activeQualifications = jdbc.query("""
                        SELECT id, position_id, effective_from
                        FROM person_qualification
                        WHERE person_id = ? AND effective_from <= ?
                          AND (effective_to IS NULL OR effective_to > ?)
                        ORDER BY position_id, effective_from
                        """,
                (rs, rowNum) -> new QualificationRow(rs.getLong("id"), rs.getLong("position_id"),
                        rs.getObject("effective_from", LocalDate.class)),
                personId, effectiveDate, effectiveDate);
        Set<Long> activePositionIds = new LinkedHashSet<>();
        for (QualificationRow qualification : activeQualifications) {
            activePositionIds.add(qualification.positionId());
            if (!desiredPositionIds.contains(qualification.positionId())) {
                if (qualification.effectiveFrom().equals(effectiveDate)) {
                    jdbc.update("DELETE FROM person_qualification WHERE id = ?", qualification.id());
                } else {
                    jdbc.update("UPDATE person_qualification SET effective_to = ? WHERE id = ?",
                            effectiveDate, qualification.id());
                }
            }
        }

        for (long positionId : desiredPositionIds) {
            if (!activePositionIds.contains(positionId)) {
                jdbc.update("""
                                INSERT INTO person_qualification (person_id, position_id, effective_from)
                                VALUES (?, ?, ?)
                                """,
                        personId, positionId, effectiveDate);
            }
        }
    }

    private long lookupEnabledId(String table, String code, String label) {
        // ponytail: table names come only from these internal call sites; user values remain SQL parameters.
        try {
            Long id = jdbc.queryForObject("SELECT id FROM " + table + " WHERE code = ? AND enabled", Long.class, code);
            if (id == null) {
                throw badRequest("未知或停用的" + label + "编码：" + code);
            }
            return id;
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw badRequest("未知或停用的" + label + "编码：" + code);
        }
    }

    private static Object[] append(List<Long> ids, Object... tail) {
        List<Object> values = new ArrayList<>(ids);
        values.addAll(List.of(tail));
        return values.toArray();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record PersonRow(long id, String name, boolean enabled, String remark, String groupCode) {
        private PersonRow(ResultSet rs) throws SQLException {
            this(rs.getLong("id"), rs.getString("name"), rs.getBoolean("enabled"),
                    rs.getString("remark"), rs.getString("group_code"));
        }
    }

    private record GroupMembershipRow(long id, long groupId, LocalDate effectiveFrom) {}

    private record QualificationRow(long id, long positionId, LocalDate effectiveFrom) {}
}
