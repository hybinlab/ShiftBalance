package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PowerBankRotation {
    // Compatibility for pre-configuration self checks; production queues are stored as person IDs.
    static final Map<String, List<String>> PERSON_ORDER = Map.of(
            "A", List.of("唐龙", "甘春霖", "刘雨鑫", "吕珈萱", "吴世富", "石蓉昌", "骆灿", "黄勇斌", "黎哲成"),
            "B", List.of("宾昕宇", "阳菲莉", "李文锦", "王小曼", "刘芳全", "黄在显", "钟绍凌", "邹运谷", "莫少波"));

    private PowerBankRotation() {}

    static List<List<PlannedDuty>> plan(
            List<GenerateRequest> requests, Map<String, String> lastAssignedNamesByGroup, LocalDate anchorWorkDate) {
        if (requests.stream().allMatch(r -> lateGroupFor(r) == null)) return requests.stream().map(r -> List.<PlannedDuty>of()).toList();
        return plan(requests, lastAssignedNamesByGroup, anchorWorkDate, "A", "B");
    }

    static List<List<PlannedDuty>> plan(List<GenerateRequest> requests, Map<String, String> lastAssignedIds,
                                       LocalDate anchor, String firstGroup, String otherGroup) {
        Map<String, String> cursors = new LinkedHashMap<>(lastAssignedIds);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) order.add(i);
        order.sort(Comparator.comparing(i -> requests.get(i).scheduleDate()));
        List<List<PlannedDuty>> plans = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) plans.add(List.of());
        for (int index : order) {
            GenerateRequest request = requests.get(index);
            SchedulingRules rules = request.rules();
            if (!rules.powerBankEnabled() || !rules.workday(anchor, request.scheduleDate())) continue;
            var day = rules.day(anchor, request.scheduleDate());
            List<PlannedDuty> duties = new ArrayList<>();
            if (!day.morningPowerGroup().equals("NONE")) duties.add(select(request,
                    day.morningPowerGroup().equals("FIRST") ? firstGroup : otherGroup,
                    cursors, "FROM_WAREHOUSE", "MORNING_0530_0800"));
            if (!day.eveningPowerGroup().equals("NONE")) duties.add(select(request,
                    day.eveningPowerGroup().equals("FIRST") ? firstGroup : otherGroup,
                    cursors, "TO_WAREHOUSE", rules.powerBankEveningSlot()));
            plans.set(index, List.copyOf(duties));
        }
        return List.copyOf(plans);
    }

    static String lateGroupFor(GenerateRequest request) {
        String group = groupFor(request, "NIGHT_UNTIL_END", "BOMB");
        return group != null ? group : groupFor(request, "NIGHT_UNTIL_END", "OVERNIGHT");
    }

    private static PlannedDuty select(
            GenerateRequest request,
            String groupCode,
            Map<String, String> cursors,
            String direction,
            String slotCode) {
        // Legacy self-check requests have no stored ID queue; saved production rules always use IDs.
        List<String> names = request.rules().powerBankOrder().isEmpty()
                ? PERSON_ORDER.getOrDefault(groupCode, List.of())
                : request.rules().powerBankOrder().getOrDefault(groupCode, List.of());
        int previousIndex = names.indexOf(cursors.getOrDefault(groupCode, ""));
        for (int offset = 1; offset <= names.size(); offset++) {
            String name = names.get((previousIndex + offset) % names.size());
            StaffMember person = request.staff().stream()
                    .filter(StaffMember::enabled)
                    .filter(member -> member.groupCode().equals(groupCode) && (member.id().equals(name) || request.rules().powerBankOrder().isEmpty() && member.name().equals(name)))
                    .findFirst().orElse(null);
            if (person != null) {
                cursors.put(groupCode, name);
                return new PlannedDuty(direction, slotCode, groupCode, person.id(), person.name());
            }
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "推电宝轮换组 " + groupCode + " 的指定名单中没有当天可用人员，请检查姓名、组别和启用状态。");
    }

    private static String groupFor(GenerateRequest request, String slotCode, String positionCode) {
        return request.requirements().stream()
                .filter(item -> item.slotCode().equals(slotCode) && item.positionCode().equals(positionCode))
                .map(StaffingRequirement::groupCode)
                .findFirst().orElse(null);
    }

    record PlannedDuty(String direction, String slotCode, String groupCode, String personId, String personName) {}
}
