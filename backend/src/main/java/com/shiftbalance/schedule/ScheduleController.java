package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateResponse;
import com.shiftbalance.schedule.ScheduleDtos.ConfiguredGenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateFromDatabaseRequest;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import com.shiftbalance.schedule.TimeSlotCatalog.TimeSlot;
import com.shiftbalance.personnel.PersonnelService;
import com.shiftbalance.personnel.PersonnelDtos.PersonView;
import com.shiftbalance.requirement.RequirementService;
import com.shiftbalance.requirement.RequirementDtos.RequirementView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleSolver scheduleSolver;
    private final PersonnelService personnelService;
    private final RequirementService requirementService;
    private final SchedulePersistenceService persistenceService;

    public ScheduleController(
            ScheduleSolver scheduleSolver,
            PersonnelService personnelService,
            RequirementService requirementService,
            SchedulePersistenceService persistenceService) {
        this.scheduleSolver = scheduleSolver;
        this.personnelService = personnelService;
        this.requirementService = requirementService;
        this.persistenceService = persistenceService;
    }

    @GetMapping("/time-slots")
    public List<TimeSlot> timeSlots() {
        return TimeSlotCatalog.all();
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@jakarta.validation.Valid @RequestBody GenerateRequest request) {
        return response(scheduleSolver.generate(request));
    }

    @PostMapping("/generate-from-personnel")
    public ResponseEntity<GenerateResponse> generateFromPersonnel(
            @jakarta.validation.Valid @RequestBody ConfiguredGenerateRequest request) {
        List<StaffMember> staff = personnelService.people(request.scheduleDate()).stream()
                .map(PersonView::toStaffMember)
                .toList();
        GenerateRequest solverRequest = new GenerateRequest(request.scheduleDate(), staff, request.requirements());
        GenerateResponse result = scheduleSolver.generate(solverRequest);
        return response(persistenceService.saveCandidate(solverRequest, result));
    }

    @PostMapping("/generate-from-database")
    public ResponseEntity<GenerateResponse> generateFromDatabase(
            @jakarta.validation.Valid @RequestBody GenerateFromDatabaseRequest request) {
        List<RequirementView> configured = requirementService.requirements(request.scheduleDate());
        if (configured.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该日期没有可用岗位需求，请检查标准模板或配置当天例外需求。");
        }
        List<StaffingRequirement> requirements = configured.stream()
                .map(item -> new StaffingRequirement(item.slotCode(), item.positionCode(), item.positionName(),
                        item.groupCode(), item.minCount(), item.maxCount()))
                .toList();
        List<StaffMember> staff = personnelService.people(request.scheduleDate()).stream()
                .map(PersonView::toStaffMember)
                .toList();
        GenerateRequest solverRequest = new GenerateRequest(request.scheduleDate(), staff, requirements);
        GenerateResponse result = scheduleSolver.generate(solverRequest);
        return response(persistenceService.saveCandidate(solverRequest, result));
    }

    private static ResponseEntity<GenerateResponse> response(GenerateResponse response) {
        HttpStatus status = response.status().equals("INFEASIBLE")
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : response.status().equals("UNKNOWN") || response.status().equals("MODEL_INVALID")
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
