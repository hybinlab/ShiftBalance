BEGIN;

CREATE TABLE IF NOT EXISTS schedule_power_bank_duty (
    schedule_id BIGINT NOT NULL REFERENCES schedule(id) ON DELETE CASCADE,
    group_id_snapshot BIGINT NOT NULL REFERENCES staff_group(id),
    person_id BIGINT NOT NULL REFERENCES person(id),
    direction VARCHAR(20) NOT NULL CHECK (direction IN ('TO_WAREHOUSE', 'FROM_WAREHOUSE')),
    PRIMARY KEY (schedule_id, direction),
    UNIQUE (schedule_id, group_id_snapshot)
);

CREATE INDEX IF NOT EXISTS idx_schedule_power_bank_group_schedule
    ON schedule_power_bank_duty (group_id_snapshot, schedule_id);

GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE schedule_power_bank_duty
    TO shiftbalance;

COMMIT;
