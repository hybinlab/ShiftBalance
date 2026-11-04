BEGIN;

-- The first workday's late group handles both the morning return and evening delivery.
-- The primary key (schedule_id, direction) still allows exactly one person per direction.
ALTER TABLE schedule_power_bank_duty
    DROP CONSTRAINT IF EXISTS schedule_power_bank_duty_schedule_id_group_id_snapshot_key;

COMMIT;
