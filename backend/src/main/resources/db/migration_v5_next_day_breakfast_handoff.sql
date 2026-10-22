BEGIN;

-- Both morning handoff rows use the regular post coverage. The early-down
-- group works 05:30–08:00; the other group takes over 08:00–09:00.
WITH template_rows(slot_code, position_code, group_role, min_count, max_count) AS (
    VALUES
        ('NEXT_DAY_0530_0800', 'BOMB',       'EARLY_DOWN', 3, NULL::INTEGER),
        ('NEXT_DAY_0530_0800', 'TERMINAL',   'EARLY_DOWN', 0, NULL::INTEGER),
        ('NEXT_DAY_0530_0800', 'MEZZANINE', 'EARLY_DOWN', 1, 1),
        ('NEXT_DAY_0800_0900', 'BOMB',       'LATE_DOWN', 3, NULL::INTEGER),
        ('NEXT_DAY_0800_0900', 'TERMINAL',   'LATE_DOWN', 0, NULL::INTEGER),
        ('NEXT_DAY_0800_0900', 'MEZZANINE', 'LATE_DOWN', 1, 1)
)
INSERT INTO schedule_requirement_template
    (time_slot_code, position_id, group_role, min_required_count, max_required_count)
SELECT r.slot_code, position.id, r.group_role, r.min_count, r.max_count
FROM template_rows r
JOIN time_slot slot ON slot.code = r.slot_code
JOIN job_position position ON position.code = r.position_code
ON CONFLICT (time_slot_code, position_id) DO UPDATE SET
    group_role = EXCLUDED.group_role,
    min_required_count = EXCLUDED.min_required_count,
    max_required_count = EXCLUDED.max_required_count;

COMMIT;
