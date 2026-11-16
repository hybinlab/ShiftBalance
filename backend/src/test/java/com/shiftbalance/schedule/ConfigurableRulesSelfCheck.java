package com.shiftbalance.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shiftbalance.schedule.ScheduleDtos.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigurableRulesSelfCheck {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode node = mapper.valueToTree(SchedulingRules.defaults());
        node.put("powerBankEnabled", false);
        node.put("supervisorNoRepeat", false);
        node.put("flowNoRepeat", false);
        node.put("fixedExclusive", false);
        node.set("cycle", mapper.readTree("""
                [{"earlyGroup":"FIRST","morningPowerGroup":"NONE","eveningPowerGroup":"NONE"},
                 {"earlyGroup":"FIRST","morningPowerGroup":"NONE","eveningPowerGroup":"NONE"},
                 {"earlyGroup":"OTHER","morningPowerGroup":"NONE","eveningPowerGroup":"NONE"},
                 {"earlyGroup":"REST","morningPowerGroup":"NONE","eveningPowerGroup":"NONE"}]
                """));
        node.set("slots", mapper.readTree("""
                [{"code":"CUSTOM_AM","start":"08:00","end":"11:00","dayOffset":0,"onlyBeforeWorkday":false,"remainderPosition":"TERMINAL"},
                 {"code":"CUSTOM_PM","start":"13:00","end":"16:00","dayOffset":0,"onlyBeforeWorkday":false,"remainderPosition":""}]
                """));
        node.set("replacementSlots", mapper.createArrayNode());
        SchedulingRules rules = mapper.treeToValue(node, SchedulingRules.class);
        rules.validate();
        LocalDate anchor = LocalDate.of(2026, 11, 1);
        assert rules.day(anchor, anchor.plusDays(2)).earlyGroup().equals("OTHER");
        assert !rules.workday(anchor, anchor.minusDays(1));
        List<StaffMember> people = List.of(
                new StaffMember("1", "防爆", "A", true, Set.of("BOMB"), Map.of()),
                new StaffMember("2", "打饭", "A", true, Set.of("MEAL"), Map.of()),
                new StaffMember("3", "候机楼", "A", true, Set.of(), Map.of()),
                new StaffMember("4", "回流", "B", true, Set.of("FLOW"), Map.of()));
        var requirements = List.of(
                new StaffingRequirement("CUSTOM_AM", "BOMB", "防爆", "A", 1, 1, "1"),
                new StaffingRequirement("CUSTOM_AM", "MEAL", "打饭", "A", 1, 1, null),
                new StaffingRequirement("CUSTOM_AM", "TERMINAL", "候机楼", "A", 1, 1, null),
                new StaffingRequirement("CUSTOM_AM", "FLOW", "回流", "B", 1, 1, null),
                new StaffingRequirement("CUSTOM_PM", "BOMB", "防爆", "A", 1, 1, null),
                new StaffingRequirement("CUSTOM_PM", "MEAL", "打饭", "A", 1, 1, null),
                new StaffingRequirement("CUSTOM_PM", "TERMINAL", "候机楼", "A", 1, 1, null),
                new StaffingRequirement("CUSTOM_PM", "FLOW", "回流", "B", 1, 1, null));
        var solver = new ScheduleSolver();
        var result = solver.generate(new GenerateRequest(anchor, people, requirements, rules));
        assert result.assignments().size() == 8 : result;
        assert result.assignments().stream().allMatch(a -> a.people().size() == 1);
        assert result.assignments().getFirst().slotLabel().equals("08:00–11:00");
        assert result.assignments().stream().filter(a -> a.positionCode().equals("TERMINAL"))
                .allMatch(a -> a.people().getFirst().terminalSupervisor());
        node.put("flowNoRepeat", true);
        assert solver.generate(new GenerateRequest(anchor, people, requirements, mapper.treeToValue(node, SchedulingRules.class)))
                .status().equals("INFEASIBLE") : "One flow-qualified person cannot cover two slots when no-repeat is on";
        node.put("flowNoRepeat", false).put("supervisorNoRepeat", true);
        assert solver.generate(new GenerateRequest(anchor, people, requirements, mapper.treeToValue(node, SchedulingRules.class)))
                .status().equals("INFEASIBLE");
        node.put("supervisorNoRepeat", false).put("supervisorRequired", false);
        assert solver.generate(new GenerateRequest(anchor, people, requirements, mapper.treeToValue(node, SchedulingRules.class)))
                .assignments().stream().flatMap(a -> a.people().stream()).noneMatch(PersonSummary::terminalSupervisor);
        node.put("fixedExclusive", true);
        assert solver.generate(new GenerateRequest(anchor, people, requirements, mapper.treeToValue(node, SchedulingRules.class)))
                .status().equals("INFEASIBLE");
        // Bad time ranges are rejected rather than silently generating overlapping duties.
        ((ObjectNode) node.withArray("slots").get(1)).put("start", "10:00");
        try { mapper.treeToValue(node, SchedulingRules.class).validate(); throw new AssertionError("Overlap accepted"); }
        catch (ResponseStatusException e) { assert e.getStatusCode().value() == 400; }
        ObjectNode overlap = mapper.valueToTree(SchedulingRules.defaults());
        ((ObjectNode) overlap.withArray("slots").get(0)).put("start", "08:30");
        try { mapper.treeToValue(overlap, SchedulingRules.class).validate(); throw new AssertionError("Cross-day overlap accepted"); }
        catch (ResponseStatusException e) { assert e.getStatusCode().value() == 400; }
        checkPowerRotation(mapper, rules, people, anchor);
        if (args.length > 0 && args[0].equals("--database")) checkPersistence(mapper, rules, anchor);
        System.out.println("Configurable counts, slots, cycle, repeat switches, fixed staff and validation checks passed");
    }
    private static void checkPowerRotation(ObjectMapper mapper, SchedulingRules rules, List<StaffMember> people, LocalDate anchor) throws Exception {
        ObjectNode node = mapper.valueToTree(rules);
        node.put("powerBankEnabled", true).put("powerBankEveningSlot", "CUSTOM_PM");
        node.set("powerBankOrder", mapper.readTree("{\"A\":[\"3\",\"1\",\"2\"],\"B\":[\"4\"]}"));
        ((ObjectNode) node.withArray("cycle").get(0)).put("morningPowerGroup", "FIRST").put("eveningPowerGroup", "FIRST");
        ((ObjectNode) node.withArray("cycle").get(1)).put("eveningPowerGroup", "OTHER");
        SchedulingRules policy = mapper.treeToValue(node, SchedulingRules.class);
        policy.validate();
        var requests = List.of(new GenerateRequest(anchor, people, List.of(), policy), new GenerateRequest(anchor.plusDays(1), people, List.of(), policy));
        var plans = PowerBankRotation.plan(requests, Map.of("A", "3"), anchor, "A", "B");
        assert plans.getFirst().stream().map(PowerBankRotation.PlannedDuty::personId).toList().equals(List.of("1", "2"));
        assert plans.getFirst().getLast().slotCode().equals("CUSTOM_PM");
        assert plans.getLast().size() == 1 && plans.getLast().getFirst().personId().equals("4");
    }
    private static void checkPersistence(ObjectMapper mapper, SchedulingRules rules, LocalDate anchor) throws Exception {
        // Only connection-local temporary tables. No application data or schema is changed; always roll back.
        var properties = new java.util.Properties();
        var env = java.nio.file.Path.of(".env.local");
        if (!java.nio.file.Files.exists(env)) env = java.nio.file.Path.of("../.env.local");
        if (java.nio.file.Files.exists(env)) try (var reader = java.nio.file.Files.newBufferedReader(env)) { properties.load(reader); }
        for (String key : List.of("SHIFTBALANCE_DB_URL", "SHIFTBALANCE_DB_USER", "SHIFTBALANCE_DB_PASSWORD")) {
            if (System.getenv(key) != null) properties.setProperty(key, System.getenv(key));
        }
        try (var connection = java.sql.DriverManager.getConnection(properties.getProperty("SHIFTBALANCE_DB_URL"),
                properties.getProperty("SHIFTBALANCE_DB_USER"), properties.getProperty("SHIFTBALANCE_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                jdbc.execute("SET LOCAL search_path = pg_temp");
                for (String file : List.of("schema_v1.sql", "schema_v2_requirement_template.sql", "migration_v8_fixed_person_rules.sql",
                        "migration_v9_power_bank_rotation.sql", "migration_v12_overnight_parallel_assignment.sql", "migration_v14_power_bank_same_group.sql", "migration_v15_configurable_rules.sql")) {
                    String sql = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db", file))
                            .replace("BEGIN;", "").replace("COMMIT;", "")
                            .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMP TABLE")
                            .replace("CREATE TABLE ", "CREATE TEMP TABLE ")
                            .replaceAll("(?s)GRANT .*?;", "");
                    jdbc.execute(sql);
                }
                // Ensure all references stay in the connection's temporary namespace.
                assert jdbc.queryForObject("SELECT relpersistence = 't' FROM pg_class WHERE oid = 'person'::regclass", Boolean.class);
                for (String name : List.of("防爆", "打饭", "候机楼", "回流")) jdbc.update("INSERT INTO person(name) VALUES (?)", name);
                jdbc.execute("INSERT INTO group_membership(person_id, group_id, effective_from) SELECT id, CASE WHEN id = 4 THEN 2 ELSE 1 END, DATE '2020-01-01' FROM person");
                jdbc.execute("INSERT INTO person_qualification(person_id, position_id, effective_from) SELECT p.id, j.id, DATE '2020-01-01' FROM person p JOIN job_position j ON j.code = CASE p.id WHEN 1 THEN 'BOMB' WHEN 2 THEN 'MEAL' WHEN 4 THEN 'FLOW' ELSE 'TERMINAL' END");
                var templates = new com.shiftbalance.requirement.RequirementTemplateService(jdbc, mapper);
                var requirements = List.of(
                        new com.shiftbalance.requirement.RequirementDtos.TemplateRequirementRequest("CUSTOM_AM", "BOMB", com.shiftbalance.requirement.RequirementDtos.GroupRole.EARLY_DOWN, 1, 1, null),
                        new com.shiftbalance.requirement.RequirementDtos.TemplateRequirementRequest("CUSTOM_AM", "MEAL", com.shiftbalance.requirement.RequirementDtos.GroupRole.EARLY_DOWN, 1, 1, null),
                        new com.shiftbalance.requirement.RequirementDtos.TemplateRequirementRequest("CUSTOM_AM", "TERMINAL", com.shiftbalance.requirement.RequirementDtos.GroupRole.EARLY_DOWN, 1, null, null));
                ObjectNode node = mapper.valueToTree(rules);
                node.put("replacementEnabled", false);
                node.put("powerBankEnabled", true).put("powerBankEveningSlot", "CUSTOM_AM");
                ((ObjectNode) node.withArray("cycle").get(0)).put("morningPowerGroup", "FIRST").put("eveningPowerGroup", "FIRST");
                node.set("powerBankOrder", mapper.readTree("{\"A\":[\"3\",\"1\",\"2\"],\"B\":[\"4\"]}"));
                SchedulingRules savedRules = mapper.treeToValue(node, SchedulingRules.class);
                // This date avoids contending with a live administrator's schedule-date advisory lock.
                LocalDate date = LocalDate.of(2099, 1, 1);
                var update = new com.shiftbalance.requirement.RequirementDtos.RequirementTemplateUpdateRequest(date, "A", savedRules, requirements);
                var saved = templates.save(update);
                assert saved.rules().equals(savedRules);
                assert templates.template().requirements().equals(requirements);
                assert templates.workPeriodDates(date.plusDays(2)).size() == 3;
                assert templates.requirements(date.plusDays(3)).isEmpty();
                var daily = new com.shiftbalance.requirement.RequirementService(jdbc, templates);
                var personnel = List.of(
                        new StaffMember("1", "防爆", "A", true, Set.of("BOMB"), Map.of()),
                        new StaffMember("2", "打饭", "A", true, Set.of("MEAL"), Map.of()),
                        new StaffMember("3", "候机楼", "A", true, Set.of(), Map.of()));
                var resolved = daily.requirements(date).stream().map(r -> new StaffingRequirement(r.slotCode(), r.positionCode(), r.positionName(), r.groupCode(), r.minCount(), r.maxCount(), r.fixedPersonId())).toList();
                var request = new GenerateRequest(date, personnel, resolved, savedRules, new RotationSnapshot(date, "A"));
                var persistence = new SchedulePersistenceService(jdbc, mapper, templates);
                var generated = persistence.saveCandidate(request, new ScheduleSolver().generate(request));
                var record = persistence.getSchedule(generated.scheduleId());
                assert record.rules().equals(savedRules);
                assert record.assignments().size() == 3;
                assert record.powerBankDuties().size() == 2;
                assert record.powerBankDuties().getFirst().personId().equals("3");
                assert record.powerBankDuties().getLast().personId().equals("1");
                assert record.powerBankDuties().getLast().slotCode().equals("CUSTOM_AM");
                assert record.powerBankDuties().getFirst().personName().equals("候机楼");
                // Change current rules; the existing version must still use its own times and restrictions.
                ((ObjectNode) node.withArray("slots").get(0)).put("start", "07:30");
                node.put("supervisorNoRepeat", true).put("flowNoRepeat", true);
                templates.save(new com.shiftbalance.requirement.RequirementDtos.RequirementTemplateUpdateRequest(date, "A", mapper.treeToValue(node, SchedulingRules.class), requirements));
                assert persistence.getSchedule(record.scheduleId()).assignments().getFirst().slotLabel().equals("08:00–11:00");
                var edits = record.assignments().stream().map(a -> new ScheduleEditAssignment(a.slotCode(), a.positionCode(), a.groupCode(), a.people().stream().map(PersonSummary::id).toList(), a.people().stream().filter(PersonSummary::terminalSupervisor).map(PersonSummary::id).findFirst().orElse(null))).toList();
                var dutyEdits = record.powerBankDuties().stream().map(d -> new PowerBankDutyEdit(d.direction(), d.groupCode(), d.personId())).toList();
                persistence.editCandidate(record.scheduleId(), new EditScheduleRequest(edits, dutyEdits, Map.of()));
                assert persistence.publishCandidate(record.scheduleId()).status().equals("PUBLISHED");
                var restored = persistence.restoreAsCandidate(record.scheduleId());
                assert restored.rules().equals(savedRules);
                checkManualFallback(mapper, jdbc, persistence, request, restored);
                daily.save(date, List.of(new com.shiftbalance.requirement.RequirementDtos.RequirementRequest("CUSTOM_AM", "BOMB", "A", 1, 1, null),
                        new com.shiftbalance.requirement.RequirementDtos.RequirementRequest("CUSTOM_AM", "TERMINAL", "A", 0, null, null)));
                assert daily.requirements(date).size() == 2;
                System.out.println("PostgreSQL temporary-table checks: rule round trip, custom slots, generation, edit, publish, restore and historical snapshots passed");
            } finally { connection.rollback(); }
        }
    }

    private static void checkManualFallback(ObjectMapper mapper, org.springframework.jdbc.core.JdbcTemplate jdbc,
            SchedulePersistenceService persistence, GenerateRequest normalRequest, ScheduleResponse normal) throws Exception {
        var unrestricted = List.of(
                new ScheduleEditAssignment("CUSTOM_AM", "BOMB", "A", List.of("4"), null),
                new ScheduleEditAssignment("CUSTOM_AM", "MEAL", "A", List.of("4"), null),
                new ScheduleEditAssignment("CUSTOM_AM", "TERMINAL", "A", List.of("4"), null),
                new ScheduleEditAssignment("CUSTOM_AM", "FLOW", "A", List.of("4"), null),
                new ScheduleEditAssignment("CUSTOM_PM", "FLOW", "A", List.of("4"), null));
        try {
            persistence.editCandidate(normal.scheduleId(), new EditScheduleRequest(unrestricted, List.of(), Map.of()));
            throw new AssertionError("Normal schedules must still enforce constraints");
        } catch (ResponseStatusException e) { assert e.getStatusCode().value() == 400; }
        ObjectNode node = mapper.valueToTree(normalRequest.rules());
        node.put("powerBankEnabled", false).put("flowNoRepeat", true).put("supervisorNoRepeat", true).put("fixedExclusive", true);
        var requirements = normalRequest.requirements().stream().map(r -> new StaffingRequirement(r.slotCode(), r.positionCode(),
                r.positionName(), r.groupCode(), 10, 10, null)).toList();
        var request = new GenerateRequest(normalRequest.scheduleDate().plusDays(8), normalRequest.staff(), requirements,
                mapper.treeToValue(node, SchedulingRules.class), normalRequest.rotation());
        var failed = new ScheduleSolver().generate(request);
        assert failed.status().equals("INFEASIBLE");
        var normalResult = new ScheduleSolver().generate(normalRequest);
        var batch = persistence.saveCandidates(List.of(request, normalRequest), List.of(failed, normalResult));
        assert batch.getFirst().status().equals("MANUAL") && batch.getFirst().scheduleId() != null;
        assert !persistence.getSchedule(batch.getLast().scheduleId()).manualMode();
        long id = batch.getFirst().scheduleId();
        var manual = persistence.getSchedule(id);
        assert manual.manualMode() && !manual.manualReason().isBlank();
        assert manual.requirements().stream().anyMatch(r -> r.positionCode().equals("FLOW"));
        jdbc.update("UPDATE person SET enabled = false WHERE id = 4");
        var edited = persistence.editCandidate(id, new EditScheduleRequest(unrestricted,
                List.of(new PowerBankDutyEdit("TO_WAREHOUSE", "A", "4")), Map.of("CUSTOM_AM", "4")));
        assert edited.assignments().size() == 5 : "Cross-group, unqualified, disabled, repeated and parallel assignments are manual decisions";
        assert edited.bombReplacements().get("CUSTOM_AM").equals("4");
        assert edited.assignments().stream().flatMap(a -> a.people().stream()).noneMatch(PersonSummary::terminalSupervisor);
        try {
            persistence.editCandidate(id, new EditScheduleRequest(List.of(
                    new ScheduleEditAssignment("CUSTOM_AM", "BOMB", "A", List.of("99999999"), null)), List.of(), Map.of()));
            throw new AssertionError("Nonexistent IDs must be rejected before replacing assignments");
        } catch (ResponseStatusException e) { assert e.getStatusCode().value() == 400; }
        assert persistence.getSchedule(id).assignments().size() == 5;
        assert persistence.publishCandidate(id).status().equals("PUBLISHED") : "No rule validation in manual publication";
        var restored = persistence.restoreAsCandidate(id);
        assert restored.manualMode() && restored.manualReason().equals(manual.manualReason());
        var empty = persistence.editCandidate(restored.scheduleId(), new EditScheduleRequest(List.of(
                new ScheduleEditAssignment("CUSTOM_AM", "MEAL", "A", List.of(), null)), List.of(), Map.of()));
        assert empty.assignments().isEmpty() && empty.powerBankDuties().isEmpty() : "Manual removal must not refill any role";
        assert persistence.publishCandidate(empty.scheduleId()).status().equals("PUBLISHED");
        // An auxiliary rotation failure retains a successful solver's formal assignments in manual mode.
        ObjectNode power = mapper.valueToTree(normalRequest.rules());
        power.set("powerBankOrder", mapper.readTree("{\"A\":[\"99999999\"],\"B\":[\"4\"]}"));
        var powerRequest = new GenerateRequest(normalRequest.scheduleDate().plusDays(12), normalRequest.staff(),
                normalRequest.requirements(), mapper.treeToValue(power, SchedulingRules.class), normalRequest.rotation());
        var powerResult = persistence.saveCandidate(powerRequest, new ScheduleSolver().generate(powerRequest));
        assert powerResult.status().equals("MANUAL") && !powerResult.assignments().isEmpty();
        assert powerResult.powerBankDuties().isEmpty();
        System.out.println("Manual fallback checks passed: mixed batch, free edits, valid references, publish, restore and auxiliary failure");
    }

}
