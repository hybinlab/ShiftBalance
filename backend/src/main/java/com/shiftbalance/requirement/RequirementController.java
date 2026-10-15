package com.shiftbalance.requirement;

import com.shiftbalance.requirement.RequirementDtos.RequirementUpdateRequest;
import com.shiftbalance.requirement.RequirementDtos.RequirementView;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/schedules/{date}/requirements")
public class RequirementController {
    private final RequirementService requirementService;

    public RequirementController(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    @GetMapping
    public List<RequirementView> requirements(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return requirementService.requirements(date);
    }

    @PutMapping
    public List<RequirementView> save(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody RequirementUpdateRequest request) {
        return requirementService.save(date, request.requirements());
    }

    @DeleteMapping
    public List<RequirementView> clearOverride(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return requirementService.clearOverride(date);
    }
}
