package com.shiftbalance.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiftbalance.schedule.ScheduleDtos.EditScheduleRequest;
import com.shiftbalance.schedule.ScheduleDtos.ScheduleEditAssignment;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BombReplacementSelfCheck {
    public static void main(String[] args) throws Exception {
        String morning = "DAY_0900_1130";
        String afternoon = "DAY_1430_1730";
        var assignments = List.of(
                new ScheduleEditAssignment(morning, "BOMB", "A", List.of("1"), null),
                new ScheduleEditAssignment(morning, "TERMINAL", "A", List.of("2", "3"), "2"),
                new ScheduleEditAssignment(afternoon, "BOMB", "A", List.of("1"), null),
                new ScheduleEditAssignment(afternoon, "TERMINAL", "A", List.of("2", "3"), "3"));
        var markers = Map.of(morning, "2", afternoon, "3");
        var originalIds = Set.of("1");
        // Supervisor and replacement may overlap; these markers do not add a formal assignment.
        assert SchedulePersistenceService.checkedBombReplacements(markers, assignments, originalIds, true).equals(markers);
        ObjectMapper mapper = new ObjectMapper();
        var request = new EditScheduleRequest(assignments, null, markers);
        var reloaded = mapper.readValue(mapper.writeValueAsString(request), EditScheduleRequest.class);
        assert reloaded.equals(request);
        assert mapper.readValue("{\"assignments\":[]}", EditScheduleRequest.class).bombReplacements() == null;

        for (var invalid : List.of(Map.of(morning, "1"), Map.of(morning, "999"),
                Map.of(morning, ""), Map.of("NIGHT_UNTIL_END", "2"))) {
            expectBadRequest(() -> SchedulePersistenceService.checkedBombReplacements(invalid, assignments, originalIds, true));
        }
        expectBadRequest(() -> SchedulePersistenceService.checkedBombReplacements(markers, assignments, Set.of(), true));
        var otherGroup = List.of(assignments.getFirst(),
                new ScheduleEditAssignment(morning, "TERMINAL", "B", List.of("2"), "2"));
        expectBadRequest(() -> SchedulePersistenceService.checkedBombReplacements(Map.of(morning, "2"), otherGroup, originalIds, true));

        var moved = List.of(assignments.get(0),
                new ScheduleEditAssignment(morning, "TERMINAL", "A", List.of("3"), "3"),
                assignments.get(2), assignments.get(3));
        expectBadRequest(() -> SchedulePersistenceService.checkedBombReplacements(markers, moved, originalIds, true));
        // An older client omitting the field preserves valid markers and drops stale ones.
        assert SchedulePersistenceService.checkedBombReplacements(markers, moved, originalIds, false)
                .equals(Map.of(afternoon, "3"));
        assert SchedulePersistenceService.checkedBombReplacements(Map.of(), assignments, originalIds, true).isEmpty();
        System.out.println("Bomb replacement validation checks passed");
    }

    private static void expectBadRequest(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Invalid replacement should be rejected");
        } catch (ResponseStatusException exception) {
            assert exception.getStatusCode().value() == 400;
        }
    }
}
