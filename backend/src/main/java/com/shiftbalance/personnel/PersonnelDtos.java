package com.shiftbalance.personnel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.shiftbalance.schedule.ScheduleDtos.StaffMember;

public final class PersonnelDtos {
    private PersonnelDtos() {}

    public record GroupView(String code, String name) {}

    public record PositionView(String code, String name) {}

    public record PersonView(
            String id,
            String name,
            String groupCode,
            boolean enabled,
            String remark,
            List<String> qualifiedPositionCodes,
            Map<String, Integer> historicalCounts) {
        public StaffMember toStaffMember() {
            return new StaffMember(id, name, groupCode, enabled,
                    Set.copyOf(qualifiedPositionCodes), Map.copyOf(historicalCounts));
        }
    }

    public record CreatePersonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 20) String groupCode,
            @NotNull Boolean enabled,
            @Size(max = 500) String remark,
            @NotNull LocalDate effectiveFrom,
            @NotNull Set<@NotBlank @Size(max = 50) String> qualifiedPositionCodes) {}

    public record UpdatePersonRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull Boolean enabled,
            @Size(max = 500) String remark,
            @NotBlank @Size(max = 20) String groupCode,
            Set<@NotBlank @Size(max = 50) String> qualifiedPositionCodes) {}
}
