-- Permission simplification: collapse 2D levels into single min_level

ALTER TABLE servers DROP COLUMN min_server_level;
ALTER TABLE servers CHANGE min_internal_level min_level INT NOT NULL DEFAULT 0;
UPDATE servers SET min_level = 0;

ALTER TABLE channel_groups DROP COLUMN min_server_level;
ALTER TABLE channel_groups CHANGE min_internal_level min_level INT NOT NULL DEFAULT 0;
UPDATE channel_groups SET min_level = 0;

ALTER TABLE channels DROP COLUMN visibility_min_server_level;
ALTER TABLE channels CHANGE visibility_min_internal_level min_level INT NOT NULL DEFAULT 0;
ALTER TABLE channels DROP COLUMN speak_min_server_level;
ALTER TABLE channels CHANGE speak_min_internal_level speak_min_level INT NOT NULL DEFAULT 0;
UPDATE channels SET min_level = 0, speak_min_level = 0;

-- Refresh token metadata columns for offline refresh
ALTER TABLE auth_refresh_tokens
    ADD COLUMN username VARCHAR(100) NOT NULL DEFAULT '' AFTER user_id,
    ADD COLUMN nickname VARCHAR(100) NOT NULL DEFAULT '' AFTER username,
    ADD COLUMN email VARCHAR(255) NOT NULL DEFAULT '' AFTER nickname,
    ADD COLUMN permission_level INT NOT NULL DEFAULT 0 AFTER email,
    ADD COLUMN group_level INT NOT NULL DEFAULT 0 AFTER permission_level;
