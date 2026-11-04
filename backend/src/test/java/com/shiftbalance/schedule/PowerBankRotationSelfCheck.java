package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PowerBankRotationSelfCheck {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 20);

    public static void main(String[] args) {
        List<StaffMember> staff = new ArrayList<>();
        for (String group : List.of("A", "B")) {
            for (String name : PowerBankRotation.PERSON_ORDER.get(group)) {
                // Reverse numeric IDs and no qualifications: duties follow the agreed queue, independently of posts.
                staff.add(new StaffMember(Integer.toString(100 - staff.size()), name, group, true, Set.of(), Map.of()));
            }
        }
        staff.add(new StaffMember("1", "廖家史", "A", true, Set.of("BOMB"), Map.of()));
        var first = request(0, "B", staff);
        var second = request(1, "A", staff);
        var nextFirst = request(4, "A", staff);
        var nextSecond = request(5, "B", staff);
        var requests = List.of(first, second, nextFirst, nextSecond);
        var plans = PowerBankRotation.plan(requests, Map.of(), ANCHOR);
        assertDay(plans.get(0), "B", "宾昕宇", "B", "阳菲莉");
        assertDay(plans.get(1), "B", "李文锦", "A", "唐龙");
        assertDay(plans.get(2), "A", "甘春霖", "A", "刘雨鑫");
        assertDay(plans.get(3), "A", "吕珈萱", "B", "王小曼");

        // A single-day regeneration still uses the anchor to identify the second working day.
        var history = Map.of("B", "阳菲莉");
        assertDay(PowerBankRotation.plan(List.of(second), history, ANCHOR).getFirst(), "B", "李文锦", "A", "唐龙");
        assert history.equals(Map.of("B", "阳菲莉")) : "Planning must not consume the published cursor";
        assert PowerBankRotation.plan(List.of(first, second), Map.of(), ANCHOR).equals(plans.subList(0, 2));
        var reversed = PowerBankRotation.plan(List.of(second, first), Map.of(), ANCHOR);
        assert reversed.get(0).equals(plans.get(1)) && reversed.get(1).equals(plans.get(0));

        var wrap = PowerBankRotation.plan(List.of(first, second), Map.of("A", "黎哲成", "B", "莫少波"), ANCHOR);
        assertDay(wrap.get(0), "B", "宾昕宇", "B", "阳菲莉");
        assertDay(wrap.get(1), "B", "李文锦", "A", "唐龙");
        var disabled = staff.stream().map(person -> person.name().equals("阳菲莉")
                ? new StaffMember(person.id(), person.name(), person.groupCode(), false, Set.of(), Map.of()) : person).toList();
        assertDay(PowerBankRotation.plan(List.of(request(0, "B", disabled)), Map.of("B", "宾昕宇"), ANCHOR).getFirst(),
                "B", "李文锦", "B", "王小曼");
        // A disabled or removed cursor person still advances from their original place in the queue.
        var removed = staff.stream().filter(person -> !person.name().equals("阳菲莉")).toList();
        assertDay(PowerBankRotation.plan(List.of(request(0, "B", removed)), history, ANCHOR).getFirst(),
                "B", "李文锦", "B", "王小曼");
        assert PowerBankRotation.plan(List.of(request(2, "B", staff), request(3, "A", staff)), history, ANCHOR)
                .stream().allMatch(List::isEmpty) : "Rest days must not generate duties";
        assert PowerBankRotation.plan(List.of(new GenerateRequest(ANCHOR, staff, List.of())), Map.of(), null)
                .getFirst().isEmpty() : "Custom requests without an evening group do not require a rotation anchor";
        assertDay(PowerBankRotation.plan(List.of(request(-4, "A", staff)), Map.of(), ANCHOR).getFirst(),
                "A", "唐龙", "A", "甘春霖");
        try {
            PowerBankRotation.plan(List.of(request(0, "B", List.of(staff.getLast()))), Map.of(), ANCHOR);
            throw new AssertionError("An unavailable queue must not silently omit duties");
        } catch (ResponseStatusException exception) {
            assert exception.getStatusCode().value() == 422;
        }
        System.out.println("Power bank cycle, queue, history continuation and rest-day checks passed");
    }

    private static GenerateRequest request(int day, String lateGroup, List<StaffMember> staff) {
        String earlyGroup = lateGroup.equals("A") ? "B" : "A";
        return new GenerateRequest(ANCHOR.plusDays(day), staff, List.of(
                new StaffingRequirement("NIGHT_UNTIL_END", "BOMB", "防爆", lateGroup, 3, null, null),
                new StaffingRequirement("DAY_0900_1130", "BOMB", "防爆", earlyGroup, 3, null, null)));
    }

    private static void assertDay(List<PowerBankRotation.PlannedDuty> duties,
                                  String morningGroup, String morningName, String nightGroup, String nightName) {
        assert duties.size() == 2 : duties;
        var morning = duties.getFirst();
        var night = duties.getLast();
        assert morning.direction().equals("FROM_WAREHOUSE") && morning.slotCode().equals("MORNING_0530_0800")
                && morning.groupCode().equals(morningGroup) && morning.personName().equals(morningName) : duties;
        assert night.direction().equals("TO_WAREHOUSE") && night.slotCode().equals("NIGHT_UNTIL_END")
                && night.groupCode().equals(nightGroup) && night.personName().equals(nightName) : duties;
    }
}
