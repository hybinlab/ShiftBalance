package com.shiftbalance.rotation;

public final class DutyRotation {
    private DutyRotation() {}

    public static RotationResult next(RotationState state, String groupA, String groupB) {
        if (groupA == null || groupB == null || groupA.isBlank() || groupB.isBlank() || groupA.equals(groupB)) {
            throw new IllegalArgumentException("A/B 组编码必须是两个不同的非空值");
        }
        if (!state.firstEarlyDownGroup().equals(groupA) && !state.firstEarlyDownGroup().equals(groupB)) {
            throw new IllegalArgumentException("首日早下组必须是 A 组或 B 组");
        }
        if (state.workDayIndex() < 0 || state.workDayIndex() > 1) {
            throw new IllegalArgumentException("workDayIndex 只能是 0 或 1");
        }

        String earlyGroup = state.workDayIndex() == 0
                ? state.firstEarlyDownGroup()
                : opposite(state.firstEarlyDownGroup(), groupA, groupB);
        String lateGroup = opposite(earlyGroup, groupA, groupB);
        RotationState nextState = state.workDayIndex() == 0
                ? new RotationState(state.firstEarlyDownGroup(), 1)
                : new RotationState(earlyGroup, 0);

        return new RotationResult(earlyGroup, lateGroup, nextState);
    }

    private static String opposite(String group, String groupA, String groupB) {
        return group.equals(groupA) ? groupB : groupA;
    }

    public record RotationState(String firstEarlyDownGroup, int workDayIndex) {}

    public record RotationResult(String earlyDownGroup, String lateDownGroup, RotationState nextState) {}
}
