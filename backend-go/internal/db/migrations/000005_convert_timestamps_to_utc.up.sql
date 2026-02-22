-- Convert all existing DATETIME values from CST (Asia/Shanghai, +08:00) to UTC
-- The production database was originally created by the Python backend,
-- which stored timestamps in server local time (CST). We need them in UTC.

-- Subtract 8 hours from all CST timestamps to convert to UTC

-- messages
UPDATE messages SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE messages SET deleted_at = DATE_SUB(deleted_at, INTERVAL 8 HOUR) WHERE deleted_at IS NOT NULL;
UPDATE messages SET edited_at = DATE_SUB(edited_at, INTERVAL 8 HOUR) WHERE edited_at IS NOT NULL;

-- attachments
UPDATE attachments SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;

-- reactions
UPDATE reactions SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;

-- servers
UPDATE servers SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;

-- channels
UPDATE channels SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;

-- channel_groups
UPDATE channel_groups SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;

-- mute_records
UPDATE mute_records SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE mute_records SET muted_until = DATE_SUB(muted_until, INTERVAL 8 HOUR) WHERE muted_until IS NOT NULL;

-- voice_invites
UPDATE voice_invites SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE voice_invites SET used_at = DATE_SUB(used_at, INTERVAL 8 HOUR) WHERE used_at IS NOT NULL;

-- voice_states
UPDATE voice_states SET joined_at = DATE_SUB(joined_at, INTERVAL 8 HOUR) WHERE joined_at IS NOT NULL;

-- auth_refresh_tokens
UPDATE auth_refresh_tokens SET created_at = DATE_SUB(created_at, INTERVAL 8 HOUR) WHERE created_at IS NOT NULL;
UPDATE auth_refresh_tokens SET expires_at = DATE_SUB(expires_at, INTERVAL 8 HOUR) WHERE expires_at IS NOT NULL;

-- read_positions
UPDATE read_positions SET updated_at = DATE_SUB(updated_at, INTERVAL 8 HOUR) WHERE updated_at IS NOT NULL;

-- Change all DEFAULT values from CURRENT_TIMESTAMP to UTC_TIMESTAMP()
ALTER TABLE messages ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE attachments ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE reactions ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE servers ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE channels ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE channel_groups ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE mute_records ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE voice_invites ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE voice_states ALTER COLUMN joined_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE auth_refresh_tokens ALTER COLUMN created_at SET DEFAULT (UTC_TIMESTAMP());
ALTER TABLE read_positions ALTER COLUMN updated_at SET DEFAULT (UTC_TIMESTAMP());
