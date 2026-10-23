BEGIN;

ALTER TABLE schedule_assignment
    DROP CONSTRAINT IF EXISTS schedule_assignment_schedule_id_person_id_time_slot_code_key;

ALTER TABLE schedule_assignment
    DROP CONSTRAINT IF EXISTS schedule_assignment_one_position_per_person_slot;

ALTER TABLE schedule_assignment
    ADD CONSTRAINT schedule_assignment_one_position_per_person_slot
        UNIQUE (schedule_id, person_id, time_slot_code, position_id);

COMMIT;
