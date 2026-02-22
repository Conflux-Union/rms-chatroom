-- Reverse permission simplification: restore 2D level columns

-- Remove refresh token metadata columns
ALTER TABLE auth_refresh_tokens DROP COLUMN username;
ALTER TABLE auth_refresh_tokens DROP COLUMN nickname;
ALTER TABLE auth_refresh_tokens DROP COLUMN email;
ALTER TABLE auth_refresh_tokens DROP COLUMN permission_level;
ALTER TABLE auth_refresh_tokens DROP COLUMN group_level;

-- Restore channels 2D levels
ALTER TABLE channels CHANGE speak_min_level speak_min_internal_level INT NOT NULL DEFAULT 1;
ALTER TABLE channels ADD COLUMN speak_min_server_level INT NOT NULL DEFAULT 1 AFTER speak_min_internal_level;
ALTER TABLE channels CHANGE min_level visibility_min_internal_level INT NOT NULL DEFAULT 1;
ALTER TABLE channels ADD COLUMN visibility_min_server_level INT NOT NULL DEFAULT 1 AFTER visibility_min_internal_level;

-- Restore channel_groups 2D levels
ALTER TABLE channel_groups CHANGE min_level min_internal_level INT NOT NULL DEFAULT 1;
ALTER TABLE channel_groups ADD COLUMN min_server_level INT NOT NULL DEFAULT 1 AFTER min_internal_level;

-- Restore servers 2D levels
ALTER TABLE servers CHANGE min_level min_internal_level INT NOT NULL DEFAULT 1;
ALTER TABLE servers ADD COLUMN min_server_level INT NOT NULL DEFAULT 1 AFTER min_internal_level;
