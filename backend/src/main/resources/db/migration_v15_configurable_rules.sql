BEGIN;
ALTER TABLE schedule_rotation_config ADD COLUMN IF NOT EXISTS rules JSONB;
COMMENT ON COLUMN schedule_rotation_config.rules IS '可配置业务规则；NULL 使用迁移前默认规则。新排班保存独立快照。';
COMMIT;
