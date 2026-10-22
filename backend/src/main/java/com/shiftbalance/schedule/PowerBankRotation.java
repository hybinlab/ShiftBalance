package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PowerBankRotation {
    private PowerBankRotation() {}

    static List<List<PlannedDuty>> plan(List<GenerateRequest> requests, Map<String, String> lastAssignedByGroup) {
        Map<String, String> cursors = new LinkedHashMap<>(lastAssignedByGroup);
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) order.add(index);
        order.sort(Comparator.comparing(index -> requests.get(index).scheduleDate()));

        List<List<PlannedDuty>> plans = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) plans.add(List.of());
        for (int index : order) {
            GenerateRequest request = requests.get(index);
            String lateGroup = groupFor(request, "NIGHT_UNTIL_END", "BOMB");
            if (lateGroup == null) lateGroup = groupFor(request, "NIGHT_UNTIL_END", "OVERNIGHT");
            if (lateGroup == null) continue;

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
            // Read the configured dawn group directly so date overrides do not depend on group ordering.
            String earlyGroup = groupFor(request, "NEXT_DAY_0530_0800", "BOMB");
            if (earlyGroup == null) earlyGroup = otherGroups.getFirst();
            if (!earlyGroup.equals(otherGroups.getFirst())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "推电宝早上推回必须由当天早下组负责。");
            }
            PlannedDuty toWarehouse = select(request, lateGroup, cursors, "TO_WAREHOUSE", "NIGHT_UNTIL_END");
            PlannedDuty fromWarehouse = select(request, earlyGroup, cursors, "FROM_WAREHOUSE", "NEXT_DAY_0530_0800");
            plans.set(index, List.of(toWarehouse, fromWarehouse));
        }
        return List.copyOf(plans);
    }

    private static PlannedDuty select(
            GenerateRequest request,
            String groupCode,
            Map<String, String> cursors,
            String direction,
            String slotCode) {
        List<StaffMember> members = request.staff().stream()
                .filter(StaffMember::enabled)
                .filter(person -> person.groupCode().equals(groupCode))
                // ponytail: database ID is the stable order available today; use explicit group order if it becomes configurable.
                .sorted(Comparator.comparingLong(person -> personIdOrder(person.id())))
                .toList();
        if (members.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "推电宝轮换组 " + groupCode + " 没有启用人员。");
        }

        String lastPersonId = cursors.get(groupCode);
        int nextIndex = 0;
        if (lastPersonId != null) {
            int previousIndex = -1;
            for (int index = 0; index < members.size(); index++) {
                if (members.get(index).id().equals(lastPersonId)) {
                    previousIndex = index;
                    break;
                }
            }
            nextIndex = previousIndex >= 0
                    ? (previousIndex + 1) % members.size()
                    : indexAfter(members, lastPersonId);
        }

        StaffMember person = members.get(nextIndex);
        cursors.put(groupCode, person.id());
        return new PlannedDuty(direction, slotCode, groupCode, person.id(), person.name());
    }

    private static int indexAfter(List<StaffMember> members, String lastPersonId) {
        long previousId = personIdOrder(lastPersonId);
        for (int index = 0; index < members.size(); index++) {
            if (personIdOrder(members.get(index).id()) > previousId) return index;
        }
        return 0;
    }

    private static long personIdOrder(String personId) {
        try {
            return Long.parseLong(personId);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "推电宝轮换要求人员使用数据库数字 ID。");
        }
    }

    private static String groupFor(GenerateRequest request, String slotCode, String positionCode) {
        return request.requirements().stream()
                .filter(item -> item.slotCode().equals(slotCode) && item.positionCode().equals(positionCode))
                .map(StaffingRequirement::groupCode)
                .findFirst().orElse(null);
    }

    record PlannedDuty(String direction, String slotCode, String groupCode, String personId, String personName) {}
}
