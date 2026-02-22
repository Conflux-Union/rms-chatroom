-- Revert UTC timestamps back to CST (Asia/Shanghai, +08:00)

-- Add 8 hours to convert UTC back to CST
UPDATE messages SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE messages SET deleted_at = DATE_ADD(deleted_at, INTERVAL 8 HOUR) WHERE deleted_at IS NOT NULL;
UPDATE messages SET edited_at = DATE_ADD(edited_at, INTERVAL 8 HOUR) WHERE edited_at IS NOT NULL;
UPDATE attachments SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE reactions SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE servers SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE channels SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE channel_groups SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE mute_records SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE mute_records SET muted_until = DATE_ADD(muted_until, INTERVAL 8 HOUR) WHERE muted_until IS NOT NULL;
UPDATE voice_invites SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE voice_invites SET used_at = DATE_ADD(used_at, INTERVAL 8 HOUR) WHERE used_at IS NOT NULL;
UPDATE voice_states SET joined_at = DATE_ADD(joined_at, INTERVAL 8 HOUR) WHERE joined_at IS NOT NULL;
UPDATE auth_refresh_tokens SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE auth_refresh_tokens SET expires_at = DATE_ADD(expires_at, INTERVAL 8 HOUR) WHERE expires_at IS NOT NULL;
UPDATE read_positions SET updated_at = DATE_ADD(updated_at, INTERVAL 8 HOUR) WHERE updated_at IS NOT NULL;

-- Revert defaults back to CURRENT_TIMESTAMP
ALTER TABLE messages ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE attachments ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE reactions ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE servers ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE channels ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE channel_groups ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE mute_records ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE voice_invites ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE voice_states ALTER COLUMN joined_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE auth_refresh_tokens ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP);
ALTER TABLE read_positions ALTER COLUMN updated_at SET DEFAULT (CURRENT_TIMESTAMP);
