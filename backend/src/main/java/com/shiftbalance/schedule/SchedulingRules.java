package com.shiftbalance.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Business rules saved with each schedule; defaults only describe pre-configuration versions. */
public record SchedulingRules(
        List<CycleDay> cycle,
        List<Slot> slots,
        boolean flowNoRepeat,
        boolean supervisorRequired,
        boolean supervisorNoRepeat,
        boolean mealPreferDistinct,
        boolean balanceHistory,
        boolean positionPreferDistinct,
        boolean rolePreferences,
        boolean fixedExclusive,
        boolean replacementEnabled,
        String replacementPersonId,
        String replacementLabel,
        List<String> replacementSlots,
        boolean powerBankEnabled,
        Map<String, List<String>> powerBankOrder,
        String powerBankMorningStart,
        String powerBankMorningEnd,
        String powerBankEveningSlot) {

    public record CycleDay(String earlyGroup, String morningPowerGroup, String eveningPowerGroup) {}
    public record Slot(String code, String start, String end, int dayOffset,
                       boolean onlyBeforeWorkday, String remainderPosition) {
        public String label() {
            return (dayOffset == 0 ? "" : dayOffset == 1 ? "次日 " : dayOffset + "日后 ")
                    + start + "–" + (end == null ? "结束" : end);
        }
    }

    public static SchedulingRules defaults() {
        return new SchedulingRules(
                List.of(new CycleDay("FIRST", "OTHER", "OTHER"), new CycleDay("OTHER", "OTHER", "FIRST"),
                        new CycleDay("REST", "NONE", "NONE"), new CycleDay("REST", "NONE", "NONE"),
                        new CycleDay("OTHER", "FIRST", "FIRST"), new CycleDay("FIRST", "FIRST", "OTHER"),
                        new CycleDay("REST", "NONE", "NONE"), new CycleDay("REST", "NONE", "NONE")),
                List.of(new Slot("DAY_0900_1130", "09:00", "11:30", 0, false, "TERMINAL"),
                        new Slot("DAY_1130_1430", "11:30", "14:30", 0, false, "TERMINAL"),
                        new Slot("DAY_1430_1730", "14:30", "17:30", 0, false, "TERMINAL"),
                        new Slot("DAY_1730_1930", "17:30", "19:30", 0, false, "TERMINAL"),
                        new Slot("NIGHT_UNTIL_END", "19:30", null, 0, false, "TERMINAL"),
                        new Slot("NEXT_DAY_0530_0800", "05:30", "08:00", 1, false, "BOMB"),
                        new Slot("NEXT_DAY_0800_0900", "08:00", "09:00", 1, true, "BOMB")),
                true, true, true, true, true, true, true, true,
                true, null, "换廖", List.of("DAY_0900_1130", "DAY_1430_1730"),
                true, Map.of(), "05:30", "08:00", "NIGHT_UNTIL_END");
    }

    public int cycleIndex(LocalDate anchor, LocalDate date) {
        return (int) Math.floorMod(ChronoUnit.DAYS.between(anchor, date), cycle.size());
    }
    public CycleDay day(LocalDate anchor, LocalDate date) { return cycle.get(cycleIndex(anchor, date)); }
    public boolean workday(LocalDate anchor, LocalDate date) { return !day(anchor, date).earlyGroup().equals("REST"); }
    public Slot slot(String code) { return slots.stream().filter(s -> s.code().equals(code)).findFirst().orElse(null); }
    public String remainder(String code) { Slot s = slot(code); return s == null ? "" : s.remainderPosition(); }
    public TimeSlotCatalog.TimeSlot timeSlot(String code) {
        Slot slot = slot(code);
        return slot == null ? null : new TimeSlotCatalog.TimeSlot(code, slots.indexOf(slot) + 1, slot.label(), slot.dayOffset());
    }
    public void validate() {
        if (cycle == null || cycle.size() < 2 || cycle.size() > 60
                || cycle.stream().anyMatch(d -> d == null || d.earlyGroup() == null || d.morningPowerGroup() == null || d.eveningPowerGroup() == null || !Set.of("FIRST", "OTHER", "REST").contains(d.earlyGroup())
                || !Set.of("FIRST", "OTHER", "NONE").contains(d.morningPowerGroup())
                || !Set.of("FIRST", "OTHER", "NONE").contains(d.eveningPowerGroup()))) fail("轮班周期须包含 2–60 天，每天指定早下组或休息。");
        if (cycle.getFirst().earlyGroup().equals("REST") || !cycle.getLast().earlyGroup().equals("REST"))
            fail("周期须从工作日开始、在休息日结束，锚点对应周期第一天。");
        if (cycle.stream().anyMatch(d -> d.earlyGroup().equals("REST")
                && (!d.morningPowerGroup().equals("NONE") || !d.eveningPowerGroup().equals("NONE"))))
            fail("休息日不能配置推电宝任务。");
        if (slots == null || slots.isEmpty() || slots.size() > 40) fail("请配置 1–40 个时段。");
        Set<String> codes = new HashSet<>();
        long previousEnd = -1;
        for (Slot s : slots) {
            if (s == null || s.code() == null || !s.code().matches("[A-Z][A-Z0-9_]{0,39}") || !codes.add(s.code()))
                fail("时段编码必须唯一，由大写字母、数字和下划线组成。");
            if (s.dayOffset() < 0 || s.dayOffset() > 1 || s.remainderPosition() == null || !Set.of("", "TERMINAL", "BOMB").contains(s.remainderPosition()))
                fail("时段须为当天或次日；剩余人员可分配到候机楼、防爆或不自动分配。");
            long start = s.dayOffset() * 1440L + minute(s.start());
            long end = s.end() == null ? (s.dayOffset() + 1) * 1440L : s.dayOffset() * 1440L + minute(s.end());
            if (end <= start || start < previousEnd) fail("时段须按时间排列，结束时间须晚于开始时间，且不能重叠。");
            previousEnd = end;
        }
        for (int day = 0; day < cycle.size(); day++) {
            if (cycle.get(day).earlyGroup().equals("REST") || cycle.get((day + 1) % cycle.size()).earlyGroup().equals("REST")) continue;
            boolean followingWorkday = !cycle.get((day + 2) % cycle.size()).earlyGroup().equals("REST");
            for (Slot handoff : slots) {
                if (handoff.dayOffset() != 1) continue;
                for (Slot next : slots) {
                    if (next.dayOffset() != 0 || next.onlyBeforeWorkday() && !followingWorkday) continue;
                    if (minute(handoff.start()) < (next.end() == null ? 1440 : minute(next.end()))
                            && minute(next.start()) < (handoff.end() == null ? 1440 : minute(handoff.end())))
                        fail("次日交接时段与下一工作日的时段重叠，请调整起止时间。");
                }
            }
        }
        if (replacementSlots == null || replacementSlots.stream().anyMatch(c -> !codes.contains(c))
                || replacementSlots.size() != new HashSet<>(replacementSlots).size()) fail("替岗标记时段无效或重复。");
        if (replacementEnabled && replacementPersonId != null && !replacementPersonId.matches("[1-9][0-9]*")) fail("请选择有效的被替岗人员。");
        if (replacementLabel == null || replacementLabel.isBlank() || replacementLabel.length() > 10) fail("替岗标记文字须为 1–10 个字符。");
        if (powerBankOrder == null || powerBankOrder.values().stream().anyMatch(ids -> ids == null
                || ids.stream().anyMatch(id -> id == null || !id.matches("[1-9][0-9]*"))
                || ids.size() != new HashSet<>(ids).size())) fail("推电宝名单必须为无重复的人员列表。");
        if (minute(powerBankMorningStart) >= minute(powerBankMorningEnd)) fail("推电宝早间结束时间须晚于开始时间。");
        if (powerBankEnabled && !codes.contains(powerBankEveningSlot)) fail("请选择推电宝晚间时段。");
    }
    private static int minute(String value) {
        try {
            if (value == null || !value.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException();
            return LocalTime.parse(value).toSecondOfDay() / 60;
        } catch (RuntimeException e) { fail("时间必须为有效的 HH:mm 格式。"); return 0; }
    }
    private static void fail(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
