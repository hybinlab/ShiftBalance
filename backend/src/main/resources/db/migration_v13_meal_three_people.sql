BEGIN;

-- Update meal counts only; retain custom groups, fixed people and other posts.
UPDATE schedule_requirement_template AS requirement
SET min_required_count = 3, max_required_count = 3
FROM job_position AS position
WHERE requirement.position_id = position.id AND position.code = 'MEAL';

UPDATE daily_position_requirement AS requirement
SET min_required_count = 3, max_required_count = 3
FROM job_position AS position
WHERE requirement.position_id = position.id AND position.code = 'MEAL';

-- Saved schedules keep their assignments and configuration snapshots.
COMMIT;
