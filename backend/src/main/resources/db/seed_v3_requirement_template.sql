BEGIN;

-- ponytail: use the confirmed 2026-10-12 A-early sample as the sole rotation anchor.
INSERT INTO schedule_rotation_config
    (singleton, anchor_work_date, first_early_down_group_id)
VALUES
    (TRUE, DATE '2026-10-12', (SELECT id FROM staff_group WHERE code = 'A'))
ON CONFLICT (singleton) DO UPDATE SET
    anchor_work_date = EXCLUDED.anchor_work_date,
    first_early_down_group_id = EXCLUDED.first_early_down_group_id,
    updated_at = CURRENT_TIMESTAMP;

-- ponytail: terminal staffing is derived from the group's members after the
-- other same-group posts are assigned; early-morning handoff remains explicit.
-- Meal duty is assigned to the group not holding the main posts in that period.
WITH template_rows(slot_code, position_code, group_role, min_count, max_count) AS (
    VALUES
        ('DAY_0900_1130', 'BOMB',       'EARLY_DOWN', 3, NULL::INTEGER),
        ('DAY_0900_1130', 'TERMINAL',   'EARLY_DOWN', 0, NULL::INTEGER),
        ('DAY_0900_1130', 'MEZZANINE', 'EARLY_DOWN', 1, 1),
        ('DAY_0900_1130', 'FLOW',       'LATE_DOWN',  2, 2),
        ('DAY_0900_1130', 'MEAL',       'LATE_DOWN',  2, 2),

        ('DAY_1130_1430', 'BOMB',       'LATE_DOWN',  3, NULL::INTEGER),
        ('DAY_1130_1430', 'TERMINAL',   'LATE_DOWN',  0, NULL::INTEGER),
        ('DAY_1130_1430', 'MEZZANINE', 'LATE_DOWN',  1, 1),
        ('DAY_1130_1430', 'FLOW',       'EARLY_DOWN', 2, 2),

        ('DAY_1430_1730', 'BOMB',       'EARLY_DOWN', 3, NULL::INTEGER),
        ('DAY_1430_1730', 'TERMINAL',   'EARLY_DOWN', 0, NULL::INTEGER),
        ('DAY_1430_1730', 'MEZZANINE', 'EARLY_DOWN', 1, 1),
        ('DAY_1430_1730', 'FLOW',       'LATE_DOWN',  2, 2),
        ('DAY_1430_1730', 'MEAL',       'LATE_DOWN',  2, 2),

        ('DAY_1730_1930', 'BOMB',       'LATE_DOWN',  3, NULL::INTEGER),
        ('DAY_1730_1930', 'TERMINAL',   'LATE_DOWN',  0, NULL::INTEGER),
        ('DAY_1730_1930', 'MEZZANINE', 'LATE_DOWN',  1, 1),
        ('DAY_1730_1930', 'FLOW',       'EARLY_DOWN', 2, 2),

        ('NIGHT_UNTIL_END', 'BOMB',       'LATE_DOWN', 3, NULL::INTEGER),
        ('NIGHT_UNTIL_END', 'TERMINAL',   'LATE_DOWN', 0, NULL::INTEGER),
        ('NIGHT_UNTIL_END', 'MEZZANINE', 'LATE_DOWN', 1, 1),
        ('NIGHT_UNTIL_END', 'FLOW',       'LATE_DOWN', 1, 1),
        ('NIGHT_UNTIL_END', 'OVERNIGHT', 'LATE_DOWN', 2, 2),

        ('NEXT_DAY_0530_0800', 'BOMB',       'EARLY_DOWN', 3, NULL::INTEGER),
        ('NEXT_DAY_0530_0800', 'TERMINAL',   'EARLY_DOWN', 1, 1),
        ('NEXT_DAY_0530_0800', 'MEZZANINE', 'EARLY_DOWN', 1, 1),
        ('NEXT_DAY_0530_0800', 'MEAL',       'EARLY_DOWN', 2, 2),

        ('NEXT_DAY_0800_0900', 'BOMB',       'LATE_DOWN', 3, NULL::INTEGER),
        ('NEXT_DAY_0800_0900', 'TERMINAL',   'LATE_DOWN', 1, 1),
        ('NEXT_DAY_0800_0900', 'MEZZANINE', 'LATE_DOWN', 1, 1)
)
INSERT INTO schedule_requirement_template
    (time_slot_code, position_id, group_role, min_required_count, max_required_count)
SELECT r.slot_code, p.id, r.group_role, r.min_count, r.max_count
FROM template_rows r
JOIN time_slot t ON t.code = r.slot_code
JOIN job_position p ON p.code = r.position_code
ON CONFLICT (time_slot_code, position_id) DO UPDATE SET
    group_role = EXCLUDED.group_role,
    min_required_count = EXCLUDED.min_required_count,
    max_required_count = EXCLUDED.max_required_count;

COMMIT;
