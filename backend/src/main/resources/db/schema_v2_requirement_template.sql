BEGIN;

CREATE TABLE IF NOT EXISTS schedule_rotation_config (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    anchor_work_date DATE NOT NULL,
    first_early_down_group_id BIGINT NOT NULL REFERENCES staff_group(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedule_requirement_template (
    time_slot_code VARCHAR(40) NOT NULL REFERENCES time_slot(code),
    position_id BIGINT NOT NULL REFERENCES job_position(id),
    group_role VARCHAR(20) NOT NULL CHECK (group_role IN ('EARLY_DOWN', 'LATE_DOWN')),
    min_required_count INTEGER NOT NULL CHECK (min_required_count >= 0),
    max_required_count INTEGER,
    CHECK (max_required_count IS NULL OR max_required_count >= min_required_count),
    PRIMARY KEY (time_slot_code, position_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE schedule_rotation_config, schedule_requirement_template
    TO shiftbalance;

COMMIT;
