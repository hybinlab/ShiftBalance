package com.shiftbalance.requirement;

import jakarta.validation.Valid;
import com.shiftbalance.schedule.SchedulingRules;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public final class RequirementDtos {
    private RequirementDtos() {}

    public record RequirementUpdateRequest(@NotEmpty List<@Valid RequirementRequest> requirements) {}

    public record RequirementTemplateUpdateRequest(
            @NotNull LocalDate anchorWorkDate,
            @NotBlank String firstEarlyDownGroup,
            @NotNull SchedulingRules rules,
            @NotEmpty List<@Valid TemplateRequirementRequest> requirements) {}

    public record TemplateRequirementRequest(
            @NotBlank String slotCode,
            @NotBlank String positionCode,
            @NotNull GroupRole groupRole,
            @Min(0) int minCount,
            @PositiveOrZero Integer maxCount,
            String fixedPersonId) {}

    public enum GroupRole { EARLY_DOWN, LATE_DOWN }

    public record RequirementTemplateView(
            LocalDate anchorWorkDate,
            String firstEarlyDownGroup,
            SchedulingRules rules,
            List<TemplateRequirementRequest> requirements) {}

    public record RequirementRequest(
            @NotBlank String slotCode,
            @NotBlank String positionCode,
            @NotBlank String groupCode,
            @Min(0) int minCount,
            @PositiveOrZero Integer maxCount,
            String fixedPersonId) {}

    public record RequirementView(
            LocalDate scheduleDate,
            String slotCode,
            String positionCode,
            String positionName,
            String groupCode,
            int minCount,
            Integer maxCount,
            String fixedPersonId) {}
}
