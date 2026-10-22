package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateResponse;
import com.shiftbalance.schedule.ScheduleDtos.ConfiguredGenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateFromDatabaseRequest;
import com.shiftbalance.schedule.ScheduleDtos.GenerateWorkPeriodResponse;
import com.shiftbalance.schedule.ScheduleDtos.WorkPeriodDayResult;
import com.shiftbalance.schedule.ScheduleDtos.StaffMember;
import com.shiftbalance.schedule.ScheduleDtos.StaffingRequirement;
import com.shiftbalance.schedule.ScheduleDtos.EditScheduleRequest;
import com.shiftbalance.schedule.ScheduleDtos.ScheduleResponse;
import com.shiftbalance.schedule.ScheduleDtos.ScheduleCalendarEntry;
import com.shiftbalance.schedule.TimeSlotCatalog.TimeSlot;
import com.shiftbalance.personnel.PersonnelService;
import com.shiftbalance.personnel.PersonnelDtos.PersonView;
import com.shiftbalance.requirement.RequirementService;
import com.shiftbalance.requirement.RequirementDtos.RequirementView;
import com.shiftbalance.requirement.RequirementTemplateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleSolver scheduleSolver;
    private final PersonnelService personnelService;
    private final RequirementService requirementService;
    private final RequirementTemplateService requirementTemplateService;
    private final SchedulePersistenceService persistenceService;

    public ScheduleController(
            ScheduleSolver scheduleSolver,
            PersonnelService personnelService,
            RequirementService requirementService,
            RequirementTemplateService requirementTemplateService,
            SchedulePersistenceService persistenceService) {
        this.scheduleSolver = scheduleSolver;
        this.personnelService = personnelService;
        this.requirementService = requirementService;
        this.requirementTemplateService = requirementTemplateService;
        this.persistenceService = persistenceService;
    }

    @GetMapping("/time-slots")
    public List<TimeSlot> timeSlots() {
        return TimeSlotCatalog.all();
    }

    @GetMapping("/calendar")
    public List<ScheduleCalendarEntry> calendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate.isAfter(endDate) || ChronoUnit.DAYS.between(startDate, endDate) > 62) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日历查询范围须在 63 天以内且开始日期不能晚于结束日期。");
        }
        return persistenceService.calendar(startDate, endDate);
    }

    @GetMapping("/{scheduleId}")
    public ScheduleResponse schedule(@PathVariable long scheduleId) {
        return persistenceService.getSchedule(scheduleId);
    }

    @PutMapping("/{scheduleId}")
    public ScheduleResponse editSchedule(
            @PathVariable long scheduleId,
            @jakarta.validation.Valid @RequestBody EditScheduleRequest request) {
        return persistenceService.editCandidate(scheduleId, request);
    }

    @PostMapping("/{scheduleId}/publish")
    public ScheduleResponse publishSchedule(@PathVariable long scheduleId) {
        return persistenceService.publishCandidate(scheduleId);
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@jakarta.validation.Valid @RequestBody GenerateRequest request) {
        return response(scheduleSolver.generate(request));
    }

    @PostMapping("/generate-from-personnel")
    public ResponseEntity<GenerateResponse> generateFromPersonnel(
            @jakarta.validation.Valid @RequestBody ConfiguredGenerateRequest request) {
        List<StaffMember> staff = personnelService.people(request.scheduleDate(), Set.of(request.scheduleDate())).stream()
                .map(PersonView::toStaffMember)
                .toList();
        GenerateRequest solverRequest = new GenerateRequest(request.scheduleDate(), staff, request.requirements());
        GenerateResponse result = scheduleSolver.generate(solverRequest);
        return response(persistenceService.saveCandidate(solverRequest, result));
    }

    @PostMapping("/generate-from-database")
    public ResponseEntity<GenerateResponse> generateFromDatabase(
            @jakarta.validation.Valid @RequestBody GenerateFromDatabaseRequest request) {
        GenerateRequest solverRequest = databaseRequest(request.scheduleDate(), Set.of(request.scheduleDate()));
        GenerateResponse result = scheduleSolver.generate(solverRequest);
        return response(persistenceService.saveCandidate(solverRequest, result));
    }

    @PostMapping("/generate-work-period-from-database")
    public GenerateWorkPeriodResponse generateWorkPeriodFromDatabase(
            @jakarta.validation.Valid @RequestBody GenerateFromDatabaseRequest request) {
        LocalDate startDate = requirementTemplateService.workPeriodStartDate(request.scheduleDate());
        Set<LocalDate> replacedDates = Set.of(startDate, startDate.plusDays(1));
        GenerateRequest firstRequest = databaseRequest(startDate, replacedDates);
        GenerateRequest secondRequest = databaseRequest(startDate.plusDays(1), replacedDates);
        GenerateResponse first = scheduleSolver.generate(firstRequest);
        secondRequest = withAdditionalHistory(secondRequest, first);
        GenerateResponse second = scheduleSolver.generate(secondRequest);
        List<GenerateRequest> requests = List.of(firstRequest, secondRequest);
        List<GenerateResponse> generated = List.of(first, second);
        List<String> messages = generated.stream()
                .flatMap(result -> result.messages().stream().map(message -> result.scheduleDate() + "：" + message))
                .toList();
        boolean feasible = generated.stream()
                .allMatch(result -> result.status().equals("OPTIMAL") || result.status().equals("FEASIBLE"));
        List<GenerateResponse> schedules = feasible ? persistenceService.saveCandidates(requests, generated) : generated;
        List<WorkPeriodDayResult> days = List.of(
                new WorkPeriodDayResult(startDate, firstRequest.requirements().stream().map(requirement ->
                        new RequirementView(startDate, requirement.slotCode(), requirement.positionCode(),
                                requirement.positionName(), requirement.groupCode(), requirement.minCount(),
                                requirement.maxCount(), requirement.fixedPersonId())).toList(), schedules.get(0)),
                new WorkPeriodDayResult(startDate.plusDays(1), secondRequest.requirements().stream().map(requirement ->
                        new RequirementView(startDate.plusDays(1), requirement.slotCode(), requirement.positionCode(),
                                requirement.positionName(), requirement.groupCode(), requirement.minCount(),
                                requirement.maxCount(), requirement.fixedPersonId())).toList(), schedules.get(1)));
        String status = feasible
                ? generated.stream().allMatch(result -> result.status().equals("OPTIMAL")) ? "OPTIMAL" : "FEASIBLE"
                : generated.stream().filter(result -> !result.status().equals("OPTIMAL") && !result.status().equals("FEASIBLE"))
                        .findFirst().orElseThrow().status();
        return new GenerateWorkPeriodResponse(startDate, startDate.plusDays(1), status, days, messages);
    }

    private GenerateRequest databaseRequest(LocalDate scheduleDate, Set<LocalDate> excludedHistoryDates) {
        List<RequirementView> configured = requirementService.requirements(scheduleDate);
        if (configured.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "日期 " + scheduleDate + " 没有可用岗位需求，请检查标准模板或配置当天例外需求。");
        }
        List<StaffingRequirement> requirements = configured.stream()
                .map(item -> new StaffingRequirement(item.slotCode(), item.positionCode(), item.positionName(),
                        item.groupCode(), item.minCount(), item.maxCount(), item.fixedPersonId()))
                .toList();
        List<StaffMember> staff = personnelService.people(scheduleDate, excludedHistoryDates).stream()
                .map(PersonView::toStaffMember)
                .toList();
        return new GenerateRequest(scheduleDate, staff, requirements);
    }

    private static GenerateRequest withAdditionalHistory(GenerateRequest request, GenerateResponse previous) {
        Map<String, Map<String, Integer>> additions = new HashMap<>();
        previous.assignments().forEach(assignment -> assignment.people().forEach(person ->
                additions.computeIfAbsent(person.id(), ignored -> new HashMap<>())
                        .merge(assignment.positionCode(), 1, Integer::sum)));
        List<StaffMember> staff = request.staff().stream().map(person -> {
            Map<String, Integer> counts = new HashMap<>(person.historicalCounts());
            additions.getOrDefault(person.id(), Map.of()).forEach((position, count) -> counts.merge(position, count, Integer::sum));
            return new StaffMember(person.id(), person.name(), person.groupCode(), person.enabled(),
                    person.qualifiedPositionCodes(), counts);
        }).toList();
        return new GenerateRequest(request.scheduleDate(), staff, request.requirements());
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
