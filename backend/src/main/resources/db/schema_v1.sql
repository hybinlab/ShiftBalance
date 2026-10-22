BEGIN;

CREATE TABLE staff_group (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO staff_group (code, name) VALUES ('A', 'A组'), ('B', 'B组');

CREATE TABLE person (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    remark VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE group_membership (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person(id),
    group_id BIGINT NOT NULL REFERENCES staff_group(id),
    effective_from DATE NOT NULL,
    effective_to DATE,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (person_id, effective_from)
);
CREATE UNIQUE INDEX one_current_group_per_person
    ON group_membership (person_id) WHERE effective_to IS NULL;

CREATE TABLE job_position (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO job_position (code, name) VALUES
    ('MEAL', '打饭'),
    ('OVERNIGHT', '过夜'),
    ('BOMB', '防爆'),
    ('LEADER', '负责人'),
    ('TERMINAL', '候机楼'),
    ('MEZZANINE', '夹层'),
    ('FLOW', '回流/拉包');

CREATE TABLE person_qualification (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    person_id BIGINT NOT NULL REFERENCES person(id),
    position_id BIGINT NOT NULL REFERENCES job_position(id),
    effective_from DATE NOT NULL,
    effective_to DATE,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (person_id, position_id, effective_from)
);

CREATE TABLE time_slot (
    code VARCHAR(40) PRIMARY KEY,
    sequence_no SMALLINT NOT NULL UNIQUE,
    label VARCHAR(100) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME,
    day_offset SMALLINT NOT NULL DEFAULT 0 CHECK (day_offset >= 0),
    end_type VARCHAR(20) NOT NULL CHECK (end_type IN ('FIXED', 'FLIGHT_END')),
    CHECK ((end_type = 'FIXED' AND end_time IS NOT NULL)
        OR (end_type = 'FLIGHT_END' AND end_time IS NULL))
);

INSERT INTO time_slot (code, sequence_no, label, start_time, end_time, day_offset, end_type) VALUES
    ('DAY_0900_1130', 1, '09:00–11:30', '09:00', '11:30', 0, 'FIXED'),
    ('DAY_1130_1430', 2, '11:30–14:30', '11:30', '14:30', 0, 'FIXED'),
    ('DAY_1430_1730', 3, '14:30–17:30', '14:30', '17:30', 0, 'FIXED'),
    ('DAY_1730_1930', 4, '17:30–19:30', '17:30', '19:30', 0, 'FIXED'),
    ('NIGHT_UNTIL_END', 5, '19:30–结束', '19:30', NULL, 0, 'FLIGHT_END'),
    ('NEXT_DAY_0530_0800', 6, '次日 05:30–08:00', '05:30', '08:00', 1, 'FIXED'),
    ('NEXT_DAY_0800_0900', 7, '次日 08:00–09:00', '08:00', '09:00', 1, 'FIXED');

CREATE TABLE daily_position_requirement (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_date DATE NOT NULL,
    position_id BIGINT NOT NULL REFERENCES job_position(id),
    time_slot_code VARCHAR(40) NOT NULL REFERENCES time_slot(code),
    group_id BIGINT NOT NULL REFERENCES staff_group(id),
    min_required_count INTEGER NOT NULL CHECK (min_required_count >= 0),
    max_required_count INTEGER,
    source VARCHAR(20) NOT NULL DEFAULT 'DEFAULT' CHECK (source IN ('DEFAULT', 'MANUAL')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (max_required_count IS NULL OR max_required_count >= min_required_count),
    UNIQUE (schedule_date, position_id, time_slot_code)
);

CREATE TABLE schedule (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'CANDIDATE', 'PUBLISHED', 'CANCELLED')),
    version INTEGER NOT NULL CHECK (version > 0),
    rotation_snapshot JSONB NOT NULL DEFAULT '{}'::JSONB,
    configuration_snapshot JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    UNIQUE (schedule_date, version)
);

CREATE TABLE schedule_assignment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES schedule(id) ON DELETE CASCADE,
    person_id BIGINT NOT NULL REFERENCES person(id),
    position_id BIGINT NOT NULL REFERENCES job_position(id),
    time_slot_code VARCHAR(40) NOT NULL REFERENCES time_slot(code),
    group_id_snapshot BIGINT NOT NULL REFERENCES staff_group(id),
    terminal_supervisor BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (schedule_id, person_id, time_slot_code)
);

CREATE TABLE rotation_state (
    rotation_type VARCHAR(50) PRIMARY KEY,
    state JSONB NOT NULL,
    effective_date DATE NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

COMMIT;
