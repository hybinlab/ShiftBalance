BEGIN;

-- The previous day's early-down group works and prepares breakfast 05:30–08:00.
INSERT INTO schedule_requirement_template
    (time_slot_code, position_id, group_role, min_required_count, max_required_count)
SELECT 'NEXT_DAY_0530_0800', id, 'EARLY_DOWN', 2, 2
FROM job_position
WHERE code = 'MEAL'
ON CONFLICT (time_slot_code, position_id) DO UPDATE SET
    group_role = EXCLUDED.group_role,
    min_required_count = EXCLUDED.min_required_count,
    max_required_count = EXCLUDED.max_required_count;

COMMIT;
