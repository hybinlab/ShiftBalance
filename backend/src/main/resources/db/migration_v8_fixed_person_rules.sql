BEGIN;

ALTER TABLE schedule_requirement_template
    ADD COLUMN IF NOT EXISTS fixed_person_id BIGINT REFERENCES person(id);

ALTER TABLE daily_position_requirement
    ADD COLUMN IF NOT EXISTS fixed_person_id BIGINT REFERENCES person(id);

COMMIT;
