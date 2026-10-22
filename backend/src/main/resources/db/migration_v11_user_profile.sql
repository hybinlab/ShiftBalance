BEGIN;

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS avatar_data BYTEA,
    ADD COLUMN IF NOT EXISTS avatar_content_type VARCHAR(32);

GRANT UPDATE (username, password_hash, avatar_data, avatar_content_type)
    ON TABLE app_user TO shiftbalance;

COMMIT;
