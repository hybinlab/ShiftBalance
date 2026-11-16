package com.shiftbalance.schedule;

import com.shiftbalance.schedule.ScheduleDtos.GenerateRequest;
import com.shiftbalance.schedule.ScheduleDtos.RotationSnapshot;
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
import com.shiftbalance.schedule.ScheduleDtos.ScheduleHistoryEntry;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
        var rules = requirementTemplateService.rules();
        return rules.slots().stream().map(slot -> rules.timeSlot(slot.code())).toList();
    }

    @GetMapping("/power-bank-order")
    public Map<String, List<String>> powerBankOrder() {
        Map<String, String> names = personnelService.people(LocalDate.now(), Set.of()).stream()
                .collect(java.util.stream.Collectors.toMap(PersonView::id, PersonView::name));
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        requirementTemplateService.rules().powerBankOrder().forEach((group, ids) ->
                result.put(group, ids.stream().map(id -> names.getOrDefault(id, "人员 " + id)).toList()));
        return result;
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

    @GetMapping("/history")
    public List<ScheduleHistoryEntry> history(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "历史查询开始日期不能晚于结束日期。");
        }
        return persistenceService.history(startDate, endDate);
    }

    @GetMapping("/{scheduleId}")
    public ScheduleResponse schedule(@PathVariable long scheduleId) {
        return persistenceService.getSchedule(scheduleId);
    }

    @PostMapping("/{scheduleId}/restore")
    public ScheduleResponse restoreSchedule(@PathVariable long scheduleId) {
        return persistenceService.restoreAsCandidate(scheduleId);
    }

    @DeleteMapping("/{scheduleId}")
    public void deleteSchedule(@PathVariable long scheduleId) {
        persistenceService.deleteHistoryEntry(scheduleId);
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
        return response(scheduleSolver.generate(new GenerateRequest(request.scheduleDate(), request.staff(), request.requirements(), requirementTemplateService.rules())));
    }

    @PostMapping("/generate-from-personnel")
    public ResponseEntity<GenerateResponse> generateFromPersonnel(
            @jakarta.validation.Valid @RequestBody ConfiguredGenerateRequest request) {
        List<StaffMember> staff = personnelService.people(request.scheduleDate(), Set.of(request.scheduleDate())).stream()
                .map(PersonView::toStaffMember)
                .toList();
        var template = requirementTemplateService.template();
        GenerateRequest solverRequest = new GenerateRequest(request.scheduleDate(), staff, request.requirements(), template.rules(),
                new RotationSnapshot(template.anchorWorkDate(), template.firstEarlyDownGroup()));
        GenerateResponse result = generateCandidate(solverRequest);
        return response(persistenceService.saveCandidate(solverRequest, result));
    }

    @PostMapping("/generate-from-database")
    public ResponseEntity<GenerateResponse> generateFromDatabase(
            @jakarta.validation.Valid @RequestBody GenerateFromDatabaseRequest request) {
        GenerateRequest solverRequest = databaseRequest(request.scheduleDate(), Set.of(request.scheduleDate()));
        GenerateResponse result = generateCandidate(solverRequest);
        return response(persistenceService.saveCandidate(solverRequest, result));
    }

    @PostMapping("/generate-work-period-from-database")
    public GenerateWorkPeriodResponse generateWorkPeriodFromDatabase(
            @jakarta.validation.Valid @RequestBody GenerateFromDatabaseRequest request) {
        List<LocalDate> dates = requirementTemplateService.workPeriodDates(request.scheduleDate());
        Set<LocalDate> replacedDates = Set.copyOf(dates);
        var requests = new java.util.ArrayList<GenerateRequest>();
        var generated = new java.util.ArrayList<GenerateResponse>();
        List<GenerateRequest> inputs = dates.stream().map(date -> databaseRequest(date, replacedDates)).toList();
        for (GenerateRequest input : inputs) {
            GenerateRequest next = input;
            for (GenerateResponse previous : generated) next = withAdditionalHistory(next, previous);
            requests.add(next);
            generated.add(generateCandidate(next));
        }
        List<GenerateResponse> saved = persistenceService.saveCandidates(requests, generated);
        List<String> messages = saved.stream().flatMap(result -> result.messages().stream()
                .map(message -> result.scheduleDate() + "：" + message)).toList();
        var days = new java.util.ArrayList<WorkPeriodDayResult>();
        for (int i = 0; i < requests.size(); i++) {
            GenerateRequest r = requests.get(i);
            days.add(new WorkPeriodDayResult(r.scheduleDate(), r.requirements().stream().map(item ->
                    new RequirementView(r.scheduleDate(), item.slotCode(), item.positionCode(), item.positionName(),
                            item.groupCode(), item.minCount(), item.maxCount(), item.fixedPersonId())).toList(), saved.get(i)));
        }
        String status = saved.stream().anyMatch(r -> r.status().equals("MANUAL")) ? "MANUAL"
                : saved.stream().allMatch(r -> r.status().equals("OPTIMAL")) ? "OPTIMAL" : "FEASIBLE";
        return new GenerateWorkPeriodResponse(dates.getFirst(), dates.getLast(), status, days, messages);
    }

    private GenerateResponse generateCandidate(GenerateRequest request) {
        try {
            return scheduleSolver.generate(request);
        } catch (ResponseStatusException exception) {
            // Invalid scheduling constraints can be resolved manually; infrastructure failures still propagate.
            if (exception.getStatusCode().value() != 400 && exception.getStatusCode().value() != 422) throw exception;
            return new GenerateResponse(request.scheduleDate(), "MANUAL", List.of(),
                    List.of(exception.getReason() == null ? "自动排班配置不可用" : exception.getReason()), List.of());
        }
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
        var template = requirementTemplateService.template();
        return new GenerateRequest(scheduleDate, staff, requirements, template.rules(), new RotationSnapshot(template.anchorWorkDate(), template.firstEarlyDownGroup()));
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
        return new GenerateRequest(request.scheduleDate(), staff, request.requirements(), request.rules(), request.rotation());
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
