package com.shiftbalance.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
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
            @PositiveOrZero Integer maxCount) {}

    public record GenerateResponse(
            LocalDate scheduleDate,
            String status,
            List<Assignment> assignments,
            List<String> messages,
            List<PositionFairness> fairness,
            Long scheduleId,
            Integer version) {
        public GenerateResponse(
                LocalDate scheduleDate,
                String status,
                List<Assignment> assignments,
                List<String> messages,
                List<PositionFairness> fairness) {
            this(scheduleDate, status, assignments, messages, fairness, null, null);
        }

        public GenerateResponse withSchedule(long id, int scheduleVersion) {
            return new GenerateResponse(scheduleDate, status, assignments, messages, fairness, id, scheduleVersion);
        }
    }

    public record Assignment(
            String slotCode,
            String slotLabel,
            int slotSequence,
            String positionCode,
            String positionName,
            String groupCode,
            List<PersonSummary> people) {}

    public record PersonSummary(String id, String name) {}

    public record PositionFairness(
            String groupCode,
            String positionCode,
            int lowestProjectedCount,
            int highestProjectedCount) {}
}
