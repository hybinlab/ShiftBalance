package com.shiftbalance.schedule;

import com.shiftbalance.requirement.RequirementDtos.RequirementView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ScheduleDtos {
    private ScheduleDtos() {}

    public record GenerateRequest(
            @NotNull LocalDate scheduleDate,
            @NotEmpty List<@Valid StaffMember> staff,
            @NotEmpty List<@Valid StaffingRequirement> requirements) {}

    public record ConfiguredGenerateRequest(
            @NotNull LocalDate scheduleDate,
            @NotEmpty List<@Valid StaffingRequirement> requirements) {}

    public record GenerateFromDatabaseRequest(@NotNull LocalDate scheduleDate) {}

    public record GenerateWorkPeriodResponse(
            LocalDate workPeriodStartDate,
            LocalDate workPeriodEndDate,
            String status,
            List<WorkPeriodDayResult> days,
            List<String> messages) {}

    public record WorkPeriodDayResult(
            LocalDate scheduleDate,
            List<RequirementView> requirements,
            GenerateResponse schedule) {}

    public record EditScheduleRequest(
            @NotEmpty List<@Valid ScheduleEditAssignment> assignments,
            List<@Valid PowerBankDutyEdit> powerBankDuties,
            Map<@NotBlank String, @NotBlank String> bombReplacements) {}

    public record ScheduleEditAssignment(
            @NotBlank String slotCode,
            @NotBlank String positionCode,
            @NotBlank String groupCode,
            @NotNull List<@NotBlank String> personIds,
            String terminalSupervisorId) {}

    public record PowerBankDutyEdit(
            @NotBlank String direction,
            @NotBlank String groupCode,
            @NotBlank String personId) {}

    public record StaffMember(
            @NotBlank String id,
            @NotBlank String name,
            @NotBlank String groupCode,
            boolean enabled,
            @NotNull Set<@NotBlank String> qualifiedPositionCodes,
            @NotNull Map<@NotBlank String, @PositiveOrZero Integer> historicalCounts) {
        public StaffMember {
            qualifiedPositionCodes = qualifiedPositionCodes == null ? Set.of() : Set.copyOf(qualifiedPositionCodes);
            historicalCounts = historicalCounts == null ? Map.of() : Map.copyOf(historicalCounts);
        }
    }

    public record StaffingRequirement(
            @NotBlank String slotCode,
            @NotBlank String positionCode,
            @NotBlank String positionName,
            @NotBlank String groupCode,
            @Min(0) int minCount,
            @PositiveOrZero Integer maxCount,
            String fixedPersonId) {}

    public record GenerateResponse(
            LocalDate scheduleDate,
            String status,
            List<Assignment> assignments,
            List<String> messages,
            List<PositionFairness> fairness,
            Long scheduleId,
            Integer version,
            List<PowerBankDuty> powerBankDuties) {
        public GenerateResponse(
                LocalDate scheduleDate,
                String status,
                List<Assignment> assignments,
                List<String> messages,
                List<PositionFairness> fairness) {
            this(scheduleDate, status, assignments, messages, fairness, null, null, List.of());
        }

        public GenerateResponse(
                LocalDate scheduleDate,
                String status,
                List<Assignment> assignments,
                List<String> messages,
                List<PositionFairness> fairness,
                Long scheduleId,
                Integer version) {
            this(scheduleDate, status, assignments, messages, fairness, scheduleId, version, List.of());
        }

        public GenerateResponse withSchedule(long id, int scheduleVersion) {
            return new GenerateResponse(scheduleDate, status, assignments, messages, fairness,
                    id, scheduleVersion, powerBankDuties);
        }

        public GenerateResponse withPowerBankDuties(List<PowerBankDuty> duties) {
            return new GenerateResponse(scheduleDate, status, assignments, messages, fairness,
                    scheduleId, version, List.copyOf(duties));
        }
    }

    public record ScheduleResponse(
            long scheduleId,
            LocalDate scheduleDate,
            String status,
            int version,
            List<Assignment> assignments,
            List<PowerBankDuty> powerBankDuties,
            List<StaffingRequirement> requirements,
            Map<String, String> bombReplacements) {}

    public record ScheduleCalendarEntry(
            long scheduleId,
            LocalDate scheduleDate,
            String status,
            int version,
            List<StaffingRequirement> requirements) {}

    public record ScheduleHistoryEntry(
            long scheduleId,
            LocalDate scheduleDate,
            String status,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime publishedAt,
            int assignmentCount,
            int peopleCount,
            int powerBankDutyCount) {}

    public record PowerBankDuty(
            String direction,
            String slotCode,
            String groupCode,
            String personId,
            String personName) {}

    public record Assignment(
            String slotCode,
            String slotLabel,
            int slotSequence,
            String positionCode,
            String positionName,
            String groupCode,
            String fixedPersonId,
            List<PersonSummary> people) {}

    public record PersonSummary(String id, String name, boolean terminalSupervisor) {
        public PersonSummary(String id, String name) {
            this(id, name, false);
        }
    }

    public record PositionFairness(
            String groupCode,
            String positionCode,
            int lowestProjectedCount,
            int highestProjectedCount) {}
}
