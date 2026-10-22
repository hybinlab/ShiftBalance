BEGIN;

-- ponytail: the terminal roster is calculated by the solver from the people
-- left after other same-group posts; early handoff slots stay count-based.
UPDATE schedule_requirement_template terminal
SET min_required_count = 0,
    max_required_count = NULL
FROM job_position terminal_position
WHERE terminal.position_id = terminal_position.id
  AND terminal_position.code = 'TERMINAL'
  AND EXISTS (
      SELECT 1
      FROM schedule_requirement_template specialized
      JOIN job_position specialized_position ON specialized_position.id = specialized.position_id
      WHERE specialized.time_slot_code = terminal.time_slot_code
        AND specialized_position.code IN ('BOMB', 'MEZZANINE')
  );

-- Regular FLOW comes from the group resting during the main post block.
-- After 19:30, FLOW comes from the late-down working group instead.
UPDATE schedule_requirement_template flow
SET group_role = CASE
    WHEN flow.time_slot_code = 'NIGHT_UNTIL_END' THEN bomb.group_role
    WHEN bomb.group_role = 'EARLY_DOWN' THEN 'LATE_DOWN'
    ELSE 'EARLY_DOWN'
END
FROM job_position flow_position,
     schedule_requirement_template bomb
JOIN job_position bomb_position ON bomb_position.id = bomb.position_id
WHERE flow.position_id = flow_position.id
  AND flow_position.code = 'FLOW'
  AND bomb.position_id = bomb_position.id
  AND bomb_position.code = 'BOMB'
  AND bomb.time_slot_code = flow.time_slot_code;

COMMIT;
