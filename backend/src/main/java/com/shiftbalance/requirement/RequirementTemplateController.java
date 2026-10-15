package com.shiftbalance.requirement;

import com.shiftbalance.requirement.RequirementDtos.RequirementTemplateUpdateRequest;
import com.shiftbalance.requirement.RequirementDtos.RequirementTemplateView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules/requirements-template")
public class RequirementTemplateController {
    private final RequirementTemplateService requirementTemplateService;

    public RequirementTemplateController(RequirementTemplateService requirementTemplateService) {
        this.requirementTemplateService = requirementTemplateService;
    }

    @GetMapping
    public RequirementTemplateView template() {
        return requirementTemplateService.template();
    }

    @PutMapping
    public RequirementTemplateView save(@Valid @RequestBody RequirementTemplateUpdateRequest request) {
        return requirementTemplateService.save(request);
    }
}
