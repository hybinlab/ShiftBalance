package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                        new StaffingRequirement("DAY_0900_1130", "MEAL", "打饭", "B", 2, 2, "1"),
                        new StaffingRequirement("DAY_1130_1430", "MEAL", "打饭", "A", 2, 2, null),
                        new StaffingRequirement("DAY_1430_1730", "MEAL", "打饭", "B", 2, 2, "1")));

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

        checkOvernightIsAdditionalDuty();
        checkPowerBankRotation();
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

    private static void checkPowerBankRotation() {
        List<StaffMember> staff = List.of(
                person("1", "A员工一", "A"), person("2", "A员工二", "A"),
                person("3", "B员工一", "B"), person("4", "B员工二", "B"));
        List<GenerateRequest> requests = List.of(
                new GenerateRequest(LocalDate.of(2026, 10, 20), staff, List.of(
                        new StaffingRequirement("NIGHT_UNTIL_END", "OVERNIGHT", "过夜", "B", 1, 1, null),
                        new StaffingRequirement("NIGHT_UNTIL_END", "BOMB", "防爆", "B", 3, null, null),
                        new StaffingRequirement("NEXT_DAY_0530_0800", "BOMB", "防爆", "A", 3, null, null),
                        new StaffingRequirement("DAY_0900_1130", "BOMB", "防爆", "A", 3, null, null))),
                new GenerateRequest(LocalDate.of(2026, 10, 21), staff, List.of(
                        new StaffingRequirement("NIGHT_UNTIL_END", "OVERNIGHT", "过夜", "A", 1, 1, null),
                        new StaffingRequirement("NIGHT_UNTIL_END", "BOMB", "防爆", "A", 3, null, null),
                        new StaffingRequirement("NEXT_DAY_0530_0800", "BOMB", "防爆", "B", 3, null, null),
                        new StaffingRequirement("DAY_0900_1130", "BOMB", "防爆", "B", 3, null, null))));

        List<List<PowerBankRotation.PlannedDuty>> plans = PowerBankRotation.plan(requests, Map.of());
        assertDuty(plans.get(0).get(0), "TO_WAREHOUSE", "B", "3");
        assertDuty(plans.get(0).get(1), "FROM_WAREHOUSE", "A", "1");
        assertDuty(plans.get(1).get(0), "TO_WAREHOUSE", "A", "2");
        assertDuty(plans.get(1).get(1), "FROM_WAREHOUSE", "B", "4");
    }

    private static void assertDuty(
            PowerBankRotation.PlannedDuty duty, String direction, String groupCode, String personId) {
        if (!duty.direction().equals(direction) || !duty.groupCode().equals(groupCode)
                || !duty.personId().equals(personId)) {
            throw new AssertionError("Unexpected power bank rotation: " + duty);
        }
    }

    private static StaffMember person(String id, String name, String groupCode) {
        return new StaffMember(id, name, groupCode, true, Set.of("MEAL"), Map.of());
    }

    private static StaffMember personWithPositions(String id, String name, String groupCode, Set<String> positions) {
        return new StaffMember(id, name, groupCode, true, positions, Map.of());
    }
}
