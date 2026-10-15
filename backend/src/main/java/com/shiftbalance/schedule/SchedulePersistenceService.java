package com.shiftbalance.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiftbalance.schedule.ScheduleDtos.Assignment;
import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateResponse;
import com.shiftbalance.schedule.ScheduleDtos.PersonSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class SchedulePersistenceService {
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

        // ponytail: one scheduler allocates versions; the unique constraint prevents duplicate versions. Add a per-date advisory lock if generation becomes concurrent.
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
                                    (schedule_id, person_id, position_id, time_slot_code, group_id_snapshot)
                                VALUES (?, ?, ?, ?, ?)
                                """,
                        candidate.id(), personId, positionId, assignment.slotCode(), groupId);
            }
        }
        return result.withSchedule(candidate.id(), candidate.version());
    }

    private long referenceId(String table, String code) {
        // ponytail: table names are internal constants at call sites; values are bound parameters.
        Long id = jdbc.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
        if (id == null) {
            throw new IllegalStateException("候选排班引用了不存在的编码：" + code);
        }
        return id;
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

    private record CandidateIdentity(long id, int version) {}
}
