package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

public final class FixedPersonRuleSelfCheck {
    private FixedPersonRuleSelfCheck() {}

    public static void main(String[] args) {
        StaffMember fixed = person("1", "固定员工", "A");
        GenerateRequest request = new GenerateRequest(
                LocalDate.of(2026, 10, 20),
                List.of(
                        fixed,
                        person("2", "A员工二", "A"),
                        person("3", "A员工三", "A"),
                        person("4", "A员工四", "A"),
                        person("5", "B员工一", "B"),
                        person("6", "B员工二", "B"),
                        person("7", "B员工三", "B")),
                List.of(
                        new StaffingRequirement("DAY_0900_1130", "MEAL", "打饭", "B", 3, 3, "1"),
                        new StaffingRequirement("DAY_1130_1430", "MEAL", "打饭", "A", 3, 3, null),
                        new StaffingRequirement("DAY_1430_1730", "MEAL", "打饭", "B", 3, 3, "1")));

        var result = new ScheduleSolver().generate(request);
        if (!result.status().equals("OPTIMAL") && !result.status().equals("FEASIBLE")) {
            throw new AssertionError("Pinned cross-group schedule should be feasible: " + result.messages());
        }
        var fixedSlot = result.assignments().stream()
                .filter(item -> item.slotCode().equals("DAY_0900_1130"))
                .findFirst().orElseThrow();
        var otherSlot = result.assignments().stream()
                .filter(item -> item.slotCode().equals("DAY_1130_1430"))
                .findFirst().orElseThrow();
        var afternoonSlot = result.assignments().stream()
                .filter(item -> item.slotCode().equals("DAY_1430_1730"))
                .findFirst().orElseThrow();
        if (fixedSlot.people().stream().noneMatch(person -> person.id().equals("1"))
                || afternoonSlot.people().stream().noneMatch(person -> person.id().equals("1"))) {
            throw new AssertionError("Fixed person was not assigned to both pinned posts.");
        }
        if (otherSlot.people().stream().anyMatch(person -> person.id().equals("1"))) {
            throw new AssertionError("Fixed person was assigned outside the configured slot.");
        }
        assert result.assignments().stream().allMatch(item -> item.people().size() == 3)
                : "Every meal assignment must contain exactly three people";

        for (StaffingRequirement invalid : List.of(
                new StaffingRequirement("DAY_0900_1130", "MEAL", "打饭", "A", 2, 2, null),
                new StaffingRequirement("DAY_0900_1130", "MEAL", "打饭", "A", 3, 4, null),
                new StaffingRequirement("DAY_0900_1130", "MEAL", "打饭", "A", 3, null, null))) {
            try {
                new ScheduleSolver().generate(new GenerateRequest(request.scheduleDate(), request.staff(), List.of(invalid)));
                throw new AssertionError("Meal counts other than exactly three should be rejected");
            } catch (ResponseStatusException exception) {
                assert exception.getStatusCode().value() == 400;
            }
        }

        checkBreakfastStaffing();
        checkMealRotation();
        checkOvernightIsAdditionalDuty();
        PowerBankRotationSelfCheck.main(new String[0]);
        System.out.println("Fixed person, three-person meal, overnight and power bank checks passed");
    }

    private static void checkBreakfastStaffing() {
        var people = IntStream.rangeClosed(1, 8)
                .mapToObj(id -> personWithPositions(Integer.toString(id), "员工" + id, "A",
                        Set.of("BOMB", "TERMINAL", "MEZZANINE", "MEAL")))
                .toList();
        var requirements = List.of(
                new StaffingRequirement("NEXT_DAY_0530_0800", "BOMB", "防爆", "A", 3, null, null),
                new StaffingRequirement("NEXT_DAY_0530_0800", "TERMINAL", "候机楼", "A", 1, 1, null),
                new StaffingRequirement("NEXT_DAY_0530_0800", "MEZZANINE", "夹层", "A", 1, 1, null),
                new StaffingRequirement("NEXT_DAY_0530_0800", "MEAL", "打饭", "A", 3, 3, null));
        var result = new ScheduleSolver().generate(new GenerateRequest(LocalDate.of(2026, 11, 4), people, requirements));
        assert result.status().equals("OPTIMAL") || result.status().equals("FEASIBLE") : result.messages();
        for (var assignment : result.assignments()) {
            int expected = Set.of("BOMB", "MEAL").contains(assignment.positionCode()) ? 3 : 1;
            assert assignment.people().size() == expected : assignment;
        }
        assert result.assignments().stream().flatMap(item -> item.people().stream()).map(person -> person.id()).distinct().count() == 8
                : "Breakfast must cover the group without double-booking";
    }

    private static void checkMealRotation() {
        for (int size : List.of(9, 8)) {
            var people = IntStream.rangeClosed(1, size)
                    .mapToObj(id -> new StaffMember(Integer.toString(id), "员工" + id, "A", true,
                            Set.of("BOMB", "TERMINAL", "MEZZANINE", "MEAL"),
                            Map.of("MEAL", id == 1 ? 0 : 10)))
                    .toList();
            var requirements = List.of(
                    new StaffingRequirement("DAY_0900_1130", "MEAL", "打饭", "A", 3, 3, null),
                    new StaffingRequirement("DAY_1430_1730", "MEAL", "打饭", "A", 3, 3, null),
                    new StaffingRequirement("NEXT_DAY_0530_0800", "MEAL", "打饭", "A", 3, 3, null),
                    new StaffingRequirement("NEXT_DAY_0530_0800", "BOMB", "防爆", "A", 3, null, null),
                    new StaffingRequirement("NEXT_DAY_0530_0800", "TERMINAL", "候机楼", "A", 1, 1, null),
                    new StaffingRequirement("NEXT_DAY_0530_0800", "MEZZANINE", "夹层", "A", 1, 1, null));
            var result = new ScheduleSolver().generate(new GenerateRequest(LocalDate.of(2026, 11, 4), people, requirements));
            assert result.status().equals("OPTIMAL") || result.status().equals("FEASIBLE") : result.messages();
            var counts = new java.util.HashMap<String, Integer>();
            result.assignments().stream().filter(item -> item.positionCode().equals("MEAL")).forEach(item -> {
                assert item.people().size() == 3;
                item.people().forEach(person -> counts.merge(person.id(), 1, Integer::sum));
            });
            assert counts.size() == size : "Spread meals before compensating historical counts: " + counts;
            assert counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow() <= (size == 9 ? 1 : 2)
                    : "Only unavoidable meal repeats are allowed: " + counts;
        }
    }

    private static void checkOvernightIsAdditionalDuty() {
        StaffMember overnightEligible = new StaffMember("1", "早下兼过夜", "B", true,
                Set.of("BOMB", "OVERNIGHT"), Map.of());
        GenerateRequest request = new GenerateRequest(LocalDate.of(2026, 10, 20), List.of(
                overnightEligible,
                personWithPositions("2", "晚下员工二", "B", Set.of("BOMB")),
                personWithPositions("3", "晚下员工三", "B", Set.of("BOMB")),
                personWithPositions("4", "晚下员工四", "B", Set.of("BOMB"))), List.of(
                new StaffingRequirement("NIGHT_UNTIL_END", "BOMB", "防爆", "B", 3, 3, "1"),
                new StaffingRequirement("NIGHT_UNTIL_END", "TERMINAL", "候机楼", "B", 0, null, null),
                new StaffingRequirement("NIGHT_UNTIL_END", "OVERNIGHT", "过夜", "B", 1, 1, null)));

        var result = new ScheduleSolver().generate(request);
        if (!result.status().equals("OPTIMAL") && !result.status().equals("FEASIBLE")) {
            throw new AssertionError("Overnight must not consume the regular post assignment: " + result.messages());
        }
        boolean onBomb = result.assignments().stream()
                .filter(item -> item.positionCode().equals("BOMB"))
                .flatMap(item -> item.people().stream()).anyMatch(person -> person.id().equals("1"));
        boolean overnight = result.assignments().stream()
                .filter(item -> item.positionCode().equals("OVERNIGHT"))
                .flatMap(item -> item.people().stream()).anyMatch(person -> person.id().equals("1"));
        if (!onBomb || !overnight) {
            throw new AssertionError("The same person should cover the formal post and overnight duty.");
        }
    }

    private static StaffMember person(String id, String name, String groupCode) {
        return new StaffMember(id, name, groupCode, true, Set.of("MEAL"), Map.of());
    }

    private static StaffMember personWithPositions(String id, String name, String groupCode, Set<String> positions) {
        return new StaffMember(id, name, groupCode, true, positions, Map.of());
    }
}
