package com.shiftbalance.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiftbalance.schedule.ScheduleDtos.Assignment;
import com.shiftbalance.schedule.ScheduleDtos.EditScheduleRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateResponse;
import com.shiftbalance.schedule.ScheduleDtos.PowerBankDuty;
import com.shiftbalance.schedule.ScheduleDtos.PowerBankDutyEdit;
import com.shiftbalance.schedule.ScheduleDtos.PersonSummary;
import com.shiftbalance.schedule.ScheduleDtos.ScheduleEditAssignment;
import com.shiftbalance.schedule.ScheduleDtos.ScheduleResponse;
import com.shiftbalance.schedule.ScheduleDtos.ScheduleCalendarEntry;
import com.shiftbalance.schedule.ScheduleDtos.ScheduleHistoryEntry;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SchedulePersistenceService {
    private static final int SCHEDULE_DATE_LOCK_NAMESPACE = 0x53424643;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SchedulePersistenceService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GenerateResponse saveCandidate(GenerateRequest request, GenerateResponse result) {
        if (!result.status().equals("OPTIMAL") && !result.status().equals("FEASIBLE")) {
            return result;
        }
        List<PowerBankRotation.PlannedDuty> duties = powerBankPlans(List.of(request)).getFirst();
        return saveCandidate(request, result, duties);
    }

    private GenerateResponse saveCandidate(
            GenerateRequest request, GenerateResponse result, List<PowerBankRotation.PlannedDuty> duties) {
        lockScheduleDate(request.scheduleDate());
        jdbc.update("UPDATE schedule SET status = 'CANCELLED' WHERE schedule_date = ? AND status = 'CANDIDATE'",
                request.scheduleDate());
        CandidateIdentity candidate = jdbc.queryForObject("""
                    INSERT INTO schedule (schedule_date, status, version)
                    SELECT ?, 'CANDIDATE', COALESCE(MAX(version), 0) + 1
                    FROM schedule
                    WHERE schedule_date = ?
                    RETURNING id, version
                    """,
                (rs, rowNum) -> new CandidateIdentity(rs.getLong("id"), rs.getInt("version")),
                request.scheduleDate(), request.scheduleDate());
        if (candidate == null) {
            throw new IllegalStateException("数据库没有返回候选排班编号。");
        }

        jdbc.update("UPDATE schedule SET configuration_snapshot = ?::jsonb WHERE id = ?",
                configurationSnapshot(request), candidate.id());
        jdbc.update("DELETE FROM schedule_power_bank_duty WHERE schedule_id = ?", candidate.id());

        for (Assignment assignment : result.assignments()) {
            long positionId = referenceId("job_position", assignment.positionCode());
            long groupId = referenceId("staff_group", assignment.groupCode());
            for (PersonSummary person : assignment.people()) {
                long personId;
                try {
                    personId = Long.parseLong(person.id());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("已保存排班必须引用数据库人员 ID：" + person.id(), exception);
                }
                jdbc.update("""
                                INSERT INTO schedule_assignment
                                    (schedule_id, person_id, position_id, time_slot_code, group_id_snapshot,
                                     terminal_supervisor)
                                VALUES (?, ?, ?, ?, ?, ?)
                                """,
                        candidate.id(), personId, positionId, assignment.slotCode(), groupId,
                        person.terminalSupervisor());
            }
        }
        for (PowerBankRotation.PlannedDuty duty : duties) {
            jdbc.update("""
                            INSERT INTO schedule_power_bank_duty
                                (schedule_id, group_id_snapshot, person_id, direction)
                            VALUES (?, (SELECT id FROM staff_group WHERE code = ?), ?, ?)
                            """,
                    candidate.id(), duty.groupCode(), parsePersonId(duty.personId()), duty.direction());
        }
        return result.withSchedule(candidate.id(), candidate.version())
                .withPowerBankDuties(duties.stream().map(SchedulePersistenceService::toPowerBankDuty).toList());
    }

    @Transactional
    public List<GenerateResponse> saveCandidates(List<GenerateRequest> requests, List<GenerateResponse> results) {
        if (requests.size() != results.size()
                || results.stream().anyMatch(result -> !result.status().equals("OPTIMAL") && !result.status().equals("FEASIBLE"))) {
            throw new IllegalArgumentException("只可批量保存完整且可行的工作周期候选排班。");
        }
        List<List<PowerBankRotation.PlannedDuty>> dutyPlans = powerBankPlans(requests);
        List<GenerateResponse> saved = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            saved.add(saveCandidate(requests.get(i), results.get(i), dutyPlans.get(i)));
        }
        return List.copyOf(saved);
    }

    public ScheduleResponse getSchedule(long scheduleId) {
        ScheduleHeader header = scheduleHeader(scheduleId, false);
        Map<AssignmentKey, RequirementRule> rules = requirementRules(header.configurationSnapshot());
        Map<AssignmentKey, List<PersonSummary>> peopleByAssignment = new LinkedHashMap<>();
        Map<AssignmentKey, AssignmentDetails> details = new HashMap<>();
        jdbc.query("""
                        SELECT ts.code AS slot_code, ts.label AS slot_label, ts.sequence_no,
                               jp.code AS position_code, jp.name AS position_name,
                               g.code AS group_code, p.id AS person_id, p.name AS person_name,
                               sa.terminal_supervisor
                        FROM schedule_assignment sa
                        JOIN job_position jp ON jp.id = sa.position_id
                        JOIN staff_group g ON g.id = sa.group_id_snapshot
                        JOIN time_slot ts ON ts.code = sa.time_slot_code
                        JOIN person p ON p.id = sa.person_id
                        WHERE sa.schedule_id = ?
                        ORDER BY ts.sequence_no, jp.code, g.code, p.name, p.id
                        """,
                rs -> {
                    AssignmentKey key = new AssignmentKey(rs.getString("slot_code"),
                            rs.getString("position_code"), rs.getString("group_code"));
                    details.putIfAbsent(key, new AssignmentDetails(rs.getString("slot_label"),
                            rs.getInt("sequence_no"), rs.getString("position_name")));
                    peopleByAssignment.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new PersonSummary(Long.toString(rs.getLong("person_id")),
                                    rs.getString("person_name"), rs.getBoolean("terminal_supervisor")));
                }, scheduleId);
        List<Assignment> assignments = peopleByAssignment.entrySet().stream()
                .map(entry -> {
                    AssignmentDetails item = details.get(entry.getKey());
                    return new Assignment(entry.getKey().slotCode(), item.slotLabel(), item.slotSequence(),
                            entry.getKey().positionCode(), item.positionName(), entry.getKey().groupCode(),
                            rules.getOrDefault(entry.getKey(), new RequirementRule(0, null, null)).fixedPersonId(),
                            List.copyOf(entry.getValue()));
                }).toList();
        List<PowerBankDuty> powerBankDuties = jdbc.query("""
                        SELECT duty.direction, staff_group.code AS group_code,
                               person.id AS person_id, person.name AS person_name
                        FROM schedule_power_bank_duty duty
                        JOIN staff_group ON staff_group.id = duty.group_id_snapshot
                        JOIN person ON person.id = duty.person_id
                        WHERE duty.schedule_id = ?
                        ORDER BY CASE duty.direction WHEN 'TO_WAREHOUSE' THEN 1 ELSE 2 END
                        """,
                (rs, rowNum) -> toPowerBankDuty(rs.getString("direction"), rs.getString("group_code"),
                        Long.toString(rs.getLong("person_id")), rs.getString("person_name")), scheduleId);
        return new ScheduleResponse(scheduleId, header.scheduleDate(), header.status(), header.version(),
                assignments, powerBankDuties, snapshotRequirements(header.configurationSnapshot()));
    }

    public List<ScheduleCalendarEntry> calendar(LocalDate startDate, LocalDate endDate) {
        return jdbc.query("""
                        SELECT DISTINCT ON (schedule_date) id, schedule_date, status, version,
                               configuration_snapshot::text AS configuration_snapshot
                        FROM schedule
                        WHERE schedule_date BETWEEN ? AND ?
                          AND status IN ('CANDIDATE', 'PUBLISHED')
                        ORDER BY schedule_date,
                                 CASE status WHEN 'CANDIDATE' THEN 0 ELSE 1 END,
                                 version DESC, id DESC
                        """,
                (rs, rowNum) -> new ScheduleCalendarEntry(
                        rs.getLong("id"), rs.getObject("schedule_date", LocalDate.class),
                        rs.getString("status"), rs.getInt("version"),
                        snapshotRequirements(rs.getString("configuration_snapshot"))),
                startDate, endDate);
    }

    public List<ScheduleHistoryEntry> history(LocalDate startDate, LocalDate endDate) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.schedule_date, s.status, s.version, s.created_at, s.published_at,
                       allocation.assignment_count, allocation.people_count, bank_stats.duty_count,
                       s.configuration_snapshot::text AS configuration_snapshot
                FROM schedule s
                CROSS JOIN LATERAL (
                    SELECT (SELECT COUNT(*)::integer FROM schedule_assignment WHERE schedule_id = s.id) AS assignment_count,
                           (SELECT COUNT(*)::integer FROM (
                               SELECT person_id FROM schedule_assignment WHERE schedule_id = s.id
                               UNION
                               SELECT person_id FROM schedule_power_bank_duty WHERE schedule_id = s.id
                           ) assigned_people) AS people_count
                ) allocation
                CROSS JOIN LATERAL (
                    SELECT COUNT(*)::integer AS duty_count
                    FROM schedule_power_bank_duty WHERE schedule_id = s.id
                ) bank_stats
                WHERE TRUE
                """);
        List<Object> parameters = new ArrayList<>();
        if (startDate != null) {
            sql.append(" AND s.schedule_date >= ?");
            parameters.add(startDate);
        }
        if (endDate != null) {
            sql.append(" AND s.schedule_date <= ?");
            parameters.add(endDate);
        }
        sql.append(" ORDER BY s.schedule_date DESC, s.version DESC, s.id DESC");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new ScheduleHistoryEntry(
                rs.getLong("id"), rs.getObject("schedule_date", LocalDate.class), rs.getString("status"),
                rs.getInt("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class), rs.getInt("assignment_count"),
                rs.getInt("people_count"), rs.getInt("duty_count")), parameters.toArray());
    }

    @Transactional
    public ScheduleResponse restoreAsCandidate(long scheduleId) {
        ScheduleHeader source = scheduleHeader(scheduleId, false);
        lockScheduleDate(source.scheduleDate());
        source = scheduleHeader(scheduleId, true);
        if (source.status().equals("CANDIDATE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该版本仍是待发布版本，无需恢复。");
        }

        jdbc.update("UPDATE schedule SET status = 'CANCELLED' WHERE schedule_date = ? AND status = 'CANDIDATE'",
                source.scheduleDate());
        CandidateIdentity restored = jdbc.queryForObject("""
                INSERT INTO schedule (schedule_date, status, version, rotation_snapshot, configuration_snapshot, created_by)
                SELECT source.schedule_date, 'CANDIDATE',
                       (SELECT COALESCE(MAX(version), 0) + 1 FROM schedule WHERE schedule_date = source.schedule_date),
                       source.rotation_snapshot, source.configuration_snapshot, source.created_by
                FROM schedule source
                WHERE source.id = ?
                RETURNING id, version
                """, (rs, rowNum) -> new CandidateIdentity(rs.getLong("id"), rs.getInt("version")), scheduleId);
        if (restored == null) throw new IllegalStateException("数据库没有返回恢复后的排班编号。");

        jdbc.update("""
                INSERT INTO schedule_assignment
                    (schedule_id, person_id, position_id, time_slot_code, group_id_snapshot, terminal_supervisor)
                SELECT ?, person_id, position_id, time_slot_code, group_id_snapshot, terminal_supervisor
                FROM schedule_assignment WHERE schedule_id = ?
                """, restored.id(), scheduleId);
        jdbc.update("""
                INSERT INTO schedule_power_bank_duty (schedule_id, group_id_snapshot, person_id, direction)
                SELECT ?, group_id_snapshot, person_id, direction
                FROM schedule_power_bank_duty WHERE schedule_id = ?
                """, restored.id(), scheduleId);
        return getSchedule(restored.id());
    }

    @Transactional
    public void deleteHistoryEntry(long scheduleId) {
        ScheduleHeader header = scheduleHeader(scheduleId, false);
        lockScheduleDate(header.scheduleDate());
        ScheduleHeader locked = scheduleHeader(scheduleId, true);
        if (!locked.status().equals(header.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "记录状态已变化，请刷新历史列表后重试。");
        }
        int deleted = jdbc.update("DELETE FROM schedule WHERE id = ?", scheduleId);
        if (deleted != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "排班记录不存在：" + scheduleId);
        }
    }

    private List<List<PowerBankRotation.PlannedDuty>> powerBankPlans(List<GenerateRequest> requests) {
        if (requests.isEmpty()) return List.of();
        LocalDate firstDate = requests.stream().map(GenerateRequest::scheduleDate).min(LocalDate::compareTo).orElseThrow();
        Map<String, String> lastAssignedByGroup = jdbc.query("""
                        SELECT DISTINCT ON (staff_group.code) staff_group.code AS group_code,
                               duty.person_id::text AS person_id
                        FROM schedule_power_bank_duty duty
                        JOIN schedule ON schedule.id = duty.schedule_id
                        JOIN staff_group ON staff_group.id = duty.group_id_snapshot
                        WHERE schedule.status = 'PUBLISHED' AND schedule.schedule_date < ?
                          AND schedule.version = (
                              SELECT MAX(latest.version) FROM schedule latest
                              WHERE latest.schedule_date = schedule.schedule_date AND latest.status = 'PUBLISHED'
                          )
                        ORDER BY staff_group.code, schedule.schedule_date DESC, schedule.version DESC
                        """,
                (rs, rowNum) -> Map.entry(rs.getString("group_code"), rs.getString("person_id")), firstDate)
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return PowerBankRotation.plan(requests, lastAssignedByGroup);
    }

    private static PowerBankDuty toPowerBankDuty(PowerBankRotation.PlannedDuty duty) {
        return toPowerBankDuty(duty.direction(), duty.groupCode(), duty.personId(), duty.personName());
    }

    private static PowerBankDuty toPowerBankDuty(
            String direction, String groupCode, String personId, String personName) {
        String slotCode = direction.equals("TO_WAREHOUSE") ? "NIGHT_UNTIL_END" : "NEXT_DAY_0530_0800";
        return new PowerBankDuty(direction, slotCode, groupCode, personId, personName);
    }

    @Transactional
    public ScheduleResponse publishCandidate(long scheduleId) {
        ScheduleHeader header = scheduleHeader(scheduleId, false);
        lockScheduleDate(header.scheduleDate());
        header = scheduleHeader(scheduleId, true);
        if (header.status().equals("PUBLISHED")) return getSchedule(scheduleId);
        if (!header.status().equals("CANDIDATE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待发布排班可以发布。");
        }

        jdbc.update("""
                UPDATE schedule SET status = 'CANCELLED'
                WHERE schedule_date = ? AND status = 'PUBLISHED' AND id <> ?
                """, header.scheduleDate(), scheduleId);
        int updated = jdbc.update("""
                UPDATE schedule SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'CANDIDATE'
                """, scheduleId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "排班状态已变化，请刷新后重试。");
        }
        return getSchedule(scheduleId);
    }

    private void lockScheduleDate(LocalDate scheduleDate) {
        // ponytail: serialize generation and publication per workday; unrelated dates remain concurrent.
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> {},
                SCHEDULE_DATE_LOCK_NAMESPACE, Math.toIntExact(scheduleDate.toEpochDay()));
    }

    @Transactional
    public ScheduleResponse editCandidate(long scheduleId, EditScheduleRequest request) {
        ScheduleHeader header = scheduleHeader(scheduleId, true);
        if (!header.status().equals("CANDIDATE")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已发布排班不能直接编辑；请生成待发布新版本后再调整。");
        }
        validateEdits(header.scheduleDate(), header.configurationSnapshot(), request.assignments());
        if (request.powerBankDuties() != null) {
            updatePowerBankDuties(scheduleId, header.scheduleDate(), request.powerBankDuties());
        }

        jdbc.update("DELETE FROM schedule_assignment WHERE schedule_id = ?", scheduleId);
        for (ScheduleEditAssignment assignment : request.assignments()) {
            long positionId = referenceId("job_position", assignment.positionCode());
            long groupId = referenceId("staff_group", assignment.groupCode());
            for (String personIdText : assignment.personIds()) {
                long personId = parsePersonId(personIdText);
                jdbc.update("""
                                INSERT INTO schedule_assignment
                                    (schedule_id, person_id, position_id, time_slot_code, group_id_snapshot,
                                     terminal_supervisor)
                                VALUES (?, ?, ?, ?, ?, ?)
                                """,
                        scheduleId, personId, positionId, assignment.slotCode(), groupId,
                        personIdText.equals(assignment.terminalSupervisorId()));
            }
        }
        return getSchedule(scheduleId);
    }

    private void updatePowerBankDuties(long scheduleId, LocalDate scheduleDate, List<PowerBankDutyEdit> edits) {
        List<ExistingPowerBankDuty> existing = jdbc.query("""
                        SELECT duty.direction, staff_group.code AS group_code
                        FROM schedule_power_bank_duty duty
                        JOIN staff_group ON staff_group.id = duty.group_id_snapshot
                        WHERE duty.schedule_id = ?
                        ORDER BY duty.direction
                        """,
                (rs, rowNum) -> new ExistingPowerBankDuty(rs.getString("direction"), rs.getString("group_code")),
                scheduleId);
        if (edits.size() != existing.size()) {
            throw badRequest("推电宝每个方向都必须保留一名人员。");
        }

        Map<String, ExistingPowerBankDuty> existingByDirection = new HashMap<>();
        existing.forEach(duty -> existingByDirection.put(duty.direction(), duty));
        Set<String> seenDirections = new HashSet<>();
        for (PowerBankDutyEdit edit : edits) {
            ExistingPowerBankDuty duty = existingByDirection.get(edit.direction());
            if (duty == null || !seenDirections.add(edit.direction())) {
                throw badRequest("推电宝方向无效或重复：" + edit.direction());
            }
            if (!duty.groupCode().equals(edit.groupCode())) {
                throw badRequest("推电宝任务不能更换组别：" + edit.direction());
            }
            long personId = parsePersonId(edit.personId());
            if (personNameInGroup(personId, scheduleDate, duty.groupCode()) == null) {
                throw badRequest("推电宝人员必须是排班日期所属组的启用人员：" + edit.personId());
            }
            jdbc.update("""
                    UPDATE schedule_power_bank_duty SET person_id = ?
                    WHERE schedule_id = ? AND direction = ?
                    """, personId, scheduleId, edit.direction());
        }
    }

    private String personNameInGroup(long personId, LocalDate date, String groupCode) {
        List<String> names = jdbc.query("""
                        SELECT p.name
                        FROM person p
                        JOIN group_membership membership ON membership.person_id = p.id
                        JOIN staff_group ON staff_group.id = membership.group_id
                        WHERE p.id = ? AND p.enabled AND staff_group.enabled AND staff_group.code = ?
                          AND membership.effective_from <= ?
                          AND (membership.effective_to IS NULL OR membership.effective_to > ?)
                        """,
                (rs, rowNum) -> rs.getString(1), personId, groupCode, date, date);
        return names.isEmpty() ? null : names.getFirst();
    }

    private void validateEdits(LocalDate scheduleDate, String configurationSnapshot,
                               List<ScheduleEditAssignment> assignments) {
        Map<AssignmentKey, RequirementRule> rules = requirementRules(configurationSnapshot);
        Set<AssignmentKey> seenAssignments = new HashSet<>();
        Set<String> seenStaffingPeopleBySlot = new HashSet<>();
        Set<String> seenOvernightPeopleBySlot = new HashSet<>();
        Map<AssignmentKey, Integer> counts = new HashMap<>();
        Map<AssignmentKey, Set<Long>> assignedPeople = new HashMap<>();
        Map<String, Integer> supervisorsBySlot = new HashMap<>();
        Set<String> supervisorIds = new HashSet<>();
        Set<Long> flowPeople = new HashSet<>();

        for (ScheduleEditAssignment assignment : assignments) {
            if (TimeSlotCatalog.find(assignment.slotCode()) == null) {
                throw badRequest("未知时段编码：" + assignment.slotCode());
            }
            AssignmentKey key = new AssignmentKey(assignment.slotCode(), assignment.positionCode(), assignment.groupCode());
            if (!seenAssignments.add(key)) {
                throw badRequest("同一时段、岗位和组别不能重复提交：" + key);
            }
            referenceId("job_position", assignment.positionCode());
            referenceId("staff_group", assignment.groupCode());
            RequirementRule rule = rules.get(key);
            if (!rules.isEmpty() && rule == null) {
                throw badRequest("该时段/岗位/组别不在此排班的需求快照中：" + key);
            }
            counts.put(key, assignment.personIds().size());

            Set<String> localPeople = new HashSet<>();
            for (String personIdText : assignment.personIds()) {
                long personId = parsePersonId(personIdText);
                String slotPerson = assignment.slotCode() + ":" + personId;
                Set<String> seenPeople = assignment.positionCode().equals("OVERNIGHT")
                        ? seenOvernightPeopleBySlot : seenStaffingPeopleBySlot;
                if (!localPeople.add(personIdText) || !seenPeople.add(slotPerson)) {
                    throw badRequest("同一人员不能在同一时段重复安排：" + personIdText);
                }
                boolean fixedPerson = rule != null && personIdText.equals(rule.fixedPersonId());
                boolean fixedElsewhere = rules.entrySet().stream()
                        .anyMatch(entry -> personIdText.equals(entry.getValue().fixedPersonId())
                                && !(entry.getKey().slotCode().equals(key.slotCode())
                                && (entry.getKey().positionCode().equals("OVERNIGHT")
                                || key.positionCode().equals("OVERNIGHT"))));
                if (fixedElsewhere && !fixedPerson) {
                    throw badRequest("固定人员只能安排在其指定时段和岗位：" + personIdText);
                }
                if (personNameIfQualified(personId, scheduleDate, assignment.groupCode(),
                        assignment.positionCode(), fixedPerson) == null) {
                    throw badRequest("人员不存在、未启用、组别不符或没有该岗位资格：" + personIdText);
                }
                if (assignment.positionCode().equals("FLOW") && !flowPeople.add(personId)) {
                    throw badRequest("回流人员当天不能重复安排：" + personIdText);
                }
                assignedPeople.computeIfAbsent(key, ignored -> new HashSet<>()).add(personId);
            }

            String supervisorId = assignment.terminalSupervisorId();
            if (assignment.positionCode().equals("TERMINAL")) {
                if (!assignment.personIds().isEmpty() && (supervisorId == null || !localPeople.contains(supervisorId))) {
                    throw badRequest("每个有人员的候机楼时段必须从已安排人员中指定一名监管人员。");
                }
                if (supervisorId != null) {
                    if (!supervisorIds.add(supervisorId)) {
                        throw badRequest("候机楼监管人员当天不能重复安排：" + supervisorId);
                    }
                    supervisorsBySlot.merge(assignment.slotCode(), 1, Integer::sum);
                }
            } else if (supervisorId != null) {
                throw badRequest("监管人员只能从候机楼岗位中指定。");
            }
        }

        for (Map.Entry<AssignmentKey, RequirementRule> entry : rules.entrySet()) {
            int count = counts.getOrDefault(entry.getKey(), 0);
            RequirementRule rule = entry.getValue();
            if (count < rule.minCount() || (rule.maxCount() != null && count > rule.maxCount())) {
                throw badRequest("人员数量不符合岗位需求：" + entry.getKey());
            }
            if (rule.fixedPersonId() != null
                    && !assignedPeople.getOrDefault(entry.getKey(), Set.of()).contains(parsePersonId(rule.fixedPersonId()))) {
                throw badRequest("固定人员必须保留在指定岗位：" + rule.fixedPersonId());
            }
        }
        for (AssignmentKey terminal : rules.keySet().stream()
                .filter(key -> key.positionCode().equals("TERMINAL"))
                .filter(key -> rules.keySet().stream().anyMatch(other -> other.slotCode().equals(key.slotCode())
                        && other.groupCode().equals(key.groupCode())
                        && (other.positionCode().equals("BOMB") || other.positionCode().equals("MEZZANINE"))))
                .toList()) {
            Set<Long> expected = activePeopleInGroup(scheduleDate, terminal.groupCode());
            Set<Long> scheduled = new HashSet<>();
            rules.keySet().stream().filter(key -> key.slotCode().equals(terminal.slotCode())
                            && key.groupCode().equals(terminal.groupCode())
                            && !key.positionCode().equals("OVERNIGHT"))
                    .forEach(key -> scheduled.addAll(assignedPeople.getOrDefault(key, Set.of())));
            rules.forEach((key, rule) -> {
                if (rule.fixedPersonId() != null
                        && !(key.positionCode().equals("OVERNIGHT") && key.slotCode().equals(terminal.slotCode()))
                        && (!key.groupCode().equals(terminal.groupCode())
                        || !key.slotCode().equals(terminal.slotCode()))) {
                    expected.remove(parsePersonId(rule.fixedPersonId()));
                }
            });
            scheduled.retainAll(expected);
            if (!scheduled.equals(expected)) {
                throw badRequest("候机楼及同组岗位必须恰好覆盖该时段的全部在岗人员：" + terminal.slotCode());
            }
        }
        if (supervisorsBySlot.values().stream().anyMatch(count -> count > 1)) {
            throw badRequest("每个时段最多指定一名候机楼监管人员。");
        }
    }

    private Map<AssignmentKey, RequirementRule> requirementRules(String snapshot) {
        try {
            JsonNode requirements = objectMapper.readTree(snapshot).path("requirements");
            if (!requirements.isArray()) {
                return Map.of();
            }
            Map<AssignmentKey, RequirementRule> rules = new LinkedHashMap<>();
            for (JsonNode item : requirements) {
                AssignmentKey key = new AssignmentKey(item.path("slotCode").asText(),
                        item.path("positionCode").asText(), item.path("groupCode").asText());
                JsonNode maxCount = item.get("maxCount");
                rules.put(key, new RequirementRule(item.path("minCount").asInt(),
                        maxCount == null || maxCount.isNull() ? null : maxCount.asInt(),
                        item.path("fixedPersonId").isMissingNode() || item.path("fixedPersonId").isNull()
                                ? null : item.path("fixedPersonId").asText()));
            }
            return rules;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取排班需求快照。", exception);
        }
    }

    private String personNameIfQualified(long personId, LocalDate date, String groupCode, String positionCode,
                                         boolean allowDifferentGroup) {
        List<String> names = jdbc.query("""
                        SELECT p.name
                        FROM person p
                        JOIN group_membership gm ON gm.person_id = p.id
                        JOIN staff_group g ON g.id = gm.group_id
                        WHERE p.id = ? AND p.enabled AND g.enabled AND (? OR g.code = ?)
                          AND gm.effective_from <= ? AND (gm.effective_to IS NULL OR gm.effective_to > ?)
                          AND EXISTS (SELECT 1 FROM job_position requested_position
                                      WHERE requested_position.code = ? AND requested_position.enabled)
                          AND (? = 'TERMINAL' OR EXISTS (
                              SELECT 1 FROM person_qualification pq
                              JOIN job_position qualified_position ON qualified_position.id = pq.position_id
                              WHERE pq.person_id = p.id AND qualified_position.code = ?
                                AND qualified_position.enabled
                                AND pq.effective_from <= ? AND (pq.effective_to IS NULL OR pq.effective_to > ?)
                          ))
                        """,
                (rs, rowNum) -> rs.getString("name"), personId, allowDifferentGroup, groupCode,
                date, date, positionCode, positionCode, positionCode, date, date);
        return names.isEmpty() ? null : names.getFirst();
    }

    private Set<Long> activePeopleInGroup(LocalDate date, String groupCode) {
        return new HashSet<>(jdbc.query("""
                        SELECT p.id
                        FROM person p
                        JOIN group_membership gm ON gm.person_id = p.id
                        JOIN staff_group g ON g.id = gm.group_id
                        WHERE p.enabled AND g.code = ?
                          AND gm.effective_from <= ? AND (gm.effective_to IS NULL OR gm.effective_to > ?)
                        """, (rs, rowNum) -> rs.getLong(1), groupCode, date, date));
    }

    private ScheduleHeader scheduleHeader(long scheduleId, boolean lock) {
        String sql = """
                SELECT id, schedule_date, status, version, configuration_snapshot::text AS configuration_snapshot
                FROM schedule WHERE id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<ScheduleHeader> rows = jdbc.query(sql,
                (rs, rowNum) -> new ScheduleHeader(rs.getLong("id"),
                        rs.getObject("schedule_date", LocalDate.class), rs.getString("status"),
                        rs.getInt("version"), rs.getString("configuration_snapshot")), scheduleId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "排班不存在：" + scheduleId);
        }
        return rows.getFirst();
    }

    private long parsePersonId(String personId) {
        try {
            return Long.parseLong(personId);
        } catch (NumberFormatException exception) {
            throw badRequest("人员 ID 必须是数据库数字 ID：" + personId);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private long referenceId(String table, String code) {
        // ponytail: table names are internal constants at call sites; values are bound parameters.
        List<Long> ids = jdbc.query("SELECT id FROM " + table + " WHERE code = ?", (rs, rowNum) -> rs.getLong(1), code);
        if (ids.isEmpty()) {
            throw badRequest("不存在的编码：" + code);
        }
        return ids.getFirst();
    }

    private String configurationSnapshot(GenerateRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "staff", request.staff(),
                    "requirements", request.requirements()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存排班配置快照。", exception);
        }
    }

    private List<StaffingRequirement> snapshotRequirements(String snapshot) {
        try {
            JsonNode requirements = objectMapper.readTree(snapshot).path("requirements");
            return requirements.isArray()
                    ? objectMapper.convertValue(requirements, new TypeReference<List<StaffingRequirement>>() {})
                    : List.of();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取排班需求快照。", exception);
        }
    }

    private record CandidateIdentity(long id, int version) {}
    private record ScheduleHeader(long id, LocalDate scheduleDate, String status, int version,
                                  String configurationSnapshot) {}
    private record AssignmentKey(String slotCode, String positionCode, String groupCode) {}
    private record AssignmentDetails(String slotLabel, int slotSequence, String positionName) {}
    private record RequirementRule(int minCount, Integer maxCount, String fixedPersonId) {}
    private record ExistingPowerBankDuty(String direction, String groupCode) {}
}
