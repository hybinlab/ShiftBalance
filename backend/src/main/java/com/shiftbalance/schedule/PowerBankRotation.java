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
    // ponytail: the agreed queues are fixed by name; store configurable person IDs if queue editing is added.
    static final Map<String, List<String>> PERSON_ORDER = Map.of(
            "A", List.of("唐龙", "甘春霖", "刘雨鑫", "吕珈萱", "吴世富", "石蓉昌", "骆灿", "黄勇斌", "黎哲成"),
            "B", List.of("宾昕宇", "阳菲莉", "李文锦", "王小曼", "刘芳全", "黄在显", "钟绍凌", "邹运谷", "莫少波"));

    private PowerBankRotation() {}

    static List<List<PlannedDuty>> plan(
            List<GenerateRequest> requests, Map<String, String> lastAssignedNamesByGroup, LocalDate anchorWorkDate) {
        Map<String, String> cursors = new LinkedHashMap<>(lastAssignedNamesByGroup);
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) order.add(index);
        order.sort(Comparator.comparing(index -> requests.get(index).scheduleDate()));

        List<List<PlannedDuty>> plans = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) plans.add(List.of());
        for (int index : order) {
            GenerateRequest request = requests.get(index);
            String lateGroup = lateGroupFor(request);
            if (lateGroup == null) continue;
            int dayInCycle = (int) Math.floorMod(ChronoUnit.DAYS.between(anchorWorkDate, request.scheduleDate()), 4);
            if (dayInCycle >= 2) continue;

            String selectedLateGroup = lateGroup;
            List<String> otherGroups = request.requirements().stream()
                    .map(StaffingRequirement::groupCode)
                    .distinct()
                    .filter(groupCode -> !groupCode.equals(selectedLateGroup))
                    .toList();
            if (otherGroups.size() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "推电宝安排需要从晚下组确定唯一的对组。");
            }
            // Both mornings belong to the first workday's late group; consume turns in time order.
            String morningGroup = dayInCycle == 0 ? lateGroup : otherGroups.getFirst();
            PlannedDuty fromWarehouse = select(request, morningGroup, cursors, "FROM_WAREHOUSE", "MORNING_0530_0800");
            PlannedDuty toWarehouse = select(request, lateGroup, cursors, "TO_WAREHOUSE", "NIGHT_UNTIL_END");
            plans.set(index, List.of(fromWarehouse, toWarehouse));
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
        List<String> names = PERSON_ORDER.getOrDefault(groupCode, List.of());
        int previousIndex = names.indexOf(cursors.getOrDefault(groupCode, ""));
        for (int offset = 1; offset <= names.size(); offset++) {
            String name = names.get((previousIndex + offset) % names.size());
            StaffMember person = request.staff().stream()
                    .filter(StaffMember::enabled)
                    .filter(member -> member.groupCode().equals(groupCode) && member.name().equals(name))
                    .findFirst().orElse(null);
            if (person != null) {
                cursors.put(groupCode, person.name());
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
