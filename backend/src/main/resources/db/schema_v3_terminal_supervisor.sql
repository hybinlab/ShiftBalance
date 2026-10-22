BEGIN;

ALTER TABLE schedule_assignment
    ADD COLUMN IF NOT EXISTS terminal_supervisor BOOLEAN NOT NULL DEFAULT FALSE;

-- A supervisor is selected from terminal staff, not scheduled as a separate job.
UPDATE job_position SET enabled = FALSE WHERE code = 'LEADER';

COMMIT;
