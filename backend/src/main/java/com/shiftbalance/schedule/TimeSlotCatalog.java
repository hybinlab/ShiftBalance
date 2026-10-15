package com.shiftbalance.schedule;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class TimeSlotCatalog {
    private static final List<TimeSlot> SLOTS = List.of(
            new TimeSlot("DAY_0900_1130", 1, "09:00–11:30", 0),
            new TimeSlot("DAY_1130_1430", 2, "11:30–14:30", 0),
            new TimeSlot("DAY_1430_1730", 3, "14:30–17:30", 0),
            new TimeSlot("DAY_1730_1930", 4, "17:30–19:30", 0),
            new TimeSlot("NIGHT_UNTIL_END", 5, "19:30–结束", 0),
            new TimeSlot("NEXT_DAY_0530_0800", 6, "次日 05:30–08:00", 1),
            new TimeSlot("NEXT_DAY_0800_0900", 7, "次日 08:00–09:00", 1)
    );
    private static final Map<String, TimeSlot> BY_CODE = SLOTS.stream()
            .collect(Collectors.toUnmodifiableMap(TimeSlot::code, Function.identity()));

    private TimeSlotCatalog() {}

    public static List<TimeSlot> all() {
        return SLOTS;
    }

    public static TimeSlot find(String code) {
        return BY_CODE.get(code);
    }

    public record TimeSlot(String code, int sequence, String label, int dayOffset) {}
}
