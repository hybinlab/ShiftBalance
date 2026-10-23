package com.shiftbalance.statistics;

import java.time.LocalDate;
import java.util.List;

public final class StatisticsDtos {
    private StatisticsDtos() {}

    public record PersonPositionAssignmentsResponse(
            LocalDate startDate,
            LocalDate endDate,
            List<PersonPositionAssignment> assignments,
            List<PowerBankAssignment> powerBankAssignments) {}

    public record PersonPositionAssignment(
            long personId,
            String personName,
            long positionId,
            String positionCode,
            String positionName,
            long groupId,
            String groupCode,
            long assignmentCount,
            LocalDate lastAssignmentDate,
            long fairnessCount,
            long eligibleSlotCount) {}

    public record PowerBankAssignment(
            long personId,
            String personName,
            long groupId,
            String groupCode,
            String direction,
            long assignmentCount,
            LocalDate lastAssignmentDate) {}

    public record PositionDistributionResponse(
            LocalDate startDate,
            LocalDate endDate,
            List<PositionDistribution> positions) {}

    public record PositionDistribution(
            long positionId,
            String positionCode,
            String positionName,
            long groupId,
            String groupCode,
            long eligiblePeople,
            long assignedPeople,
            long assignmentCount,
            long fairnessCount,
            long minFairnessCount,
            long maxFairnessCount,
            long fairnessGap,
            double averageFairnessCount) {}

    public record ScheduleStatistics(
            long scheduleId,
            LocalDate scheduleDate,
            String status,
            long assignmentCount,
            List<PersonPositionAssignment> personPositionAssignments,
            List<PositionDistribution> positionDistributions) {}
}
