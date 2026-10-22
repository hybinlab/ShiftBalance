BEGIN;

-- The two early next-day slots have one terminal supervisor; all other
-- personnel are assigned to mezzanine, breakfast (05:30 only), or BOMB.
UPDATE schedule_requirement_template AS requirement
SET min_required_count = 1,
    max_required_count = 1
FROM time_slot AS slot, job_position AS position
WHERE requirement.time_slot_code = slot.code
  AND requirement.position_id = position.id
  AND slot.code IN ('NEXT_DAY_0530_0800', 'NEXT_DAY_0800_0900')
  AND position.code = 'TERMINAL';

COMMIT;
