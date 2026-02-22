-- Initial schema for rms-discord-go
-- Translated from Python SQLAlchemy models

CREATE TABLE IF NOT EXISTS servers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    icon VARCHAR(255) NULL,
    owner_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    min_server_level INT NOT NULL DEFAULT 1,
    min_internal_level INT NOT NULL DEFAULT 1,
    INDEX idx_servers_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channel_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    server_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    min_server_level INT NOT NULL DEFAULT 1,
    min_internal_level INT NOT NULL DEFAULT 1,
    INDEX idx_channel_groups_server_id (server_id),
    CONSTRAINT fk_channel_groups_server FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS channels (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    server_id BIGINT NOT NULL,
    group_id BIGINT NULL,
    name VARCHAR(100) NOT NULL,
    type ENUM('TEXT', 'VOICE') NOT NULL DEFAULT 'TEXT',
    position INT NOT NULL DEFAULT 0,
    top_position INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    visibility_min_server_level INT NOT NULL DEFAULT 1,
    visibility_min_internal_level INT NOT NULL DEFAULT 1,
    speak_min_server_level INT NOT NULL DEFAULT 1,
    speak_min_internal_level INT NOT NULL DEFAULT 1,
    INDEX idx_channels_server_id (server_id),
    INDEX idx_channels_group_id (group_id),
    CONSTRAINT fk_channels_server FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE,
    CONSTRAINT fk_channels_group FOREIGN KEY (group_id) REFERENCES channel_groups(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at DATETIME NULL,
    deleted_by BIGINT NULL,
    edited_at DATETIME NULL,
    reply_to_id BIGINT NULL,
    INDEX idx_messages_channel_id (channel_id),
    INDEX idx_messages_user_id (user_id),
    INDEX idx_messages_created_at (created_at),
    INDEX idx_messages_channel_created (channel_id, created_at),
    INDEX idx_messages_is_deleted (is_deleted),
    INDEX idx_messages_reply_to_id (reply_to_id),
    CONSTRAINT fk_messages_channel FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_reply_to FOREIGN KEY (reply_to_id) REFERENCES messages(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message_mentions (
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (message_id, user_id),
    INDEX idx_message_mentions_user_id (user_id),
    CONSTRAINT fk_message_mentions_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NULL,
    channel_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    UNIQUE KEY uq_attachments_stored_name (stored_name),
    INDEX idx_attachments_message_id (message_id),
    INDEX idx_attachments_channel_id (channel_id),
    INDEX idx_attachments_user_id (user_id),
    CONSTRAINT fk_attachments_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_attachments_channel FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS voice_states (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    muted BOOLEAN NOT NULL DEFAULT FALSE,
    deafened BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    UNIQUE KEY uq_voice_states_channel_user (channel_id, user_id),
    INDEX idx_voice_states_channel_id (channel_id),
    INDEX idx_voice_states_user_id (user_id),
    CONSTRAINT fk_voice_states_channel FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS voice_invites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    token VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_by_name VARCHAR(100) NULL,
    used_at DATETIME NULL,
    UNIQUE KEY uq_voice_invites_token (token),
    INDEX idx_voice_invites_channel_id (channel_id),
    CONSTRAINT fk_voice_invites_channel FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mute_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    scope ENUM('global', 'server', 'channel') NOT NULL,
    server_id BIGINT NULL,
    channel_id BIGINT NULL,
    muted_until DATETIME NULL,
    muted_by BIGINT NOT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    INDEX idx_mute_records_user_id (user_id),
    INDEX idx_mute_records_server_id (server_id),
    INDEX idx_mute_records_channel_id (channel_id),
    CONSTRAINT fk_mute_records_server FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE,
    CONSTRAINT fk_mute_records_channel FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    emoji VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()),
    UNIQUE KEY uq_reaction (message_id, user_id, emoji),
    INDEX idx_reactions_message_id (message_id),
    INDEX idx_reactions_user_id (user_id),
    CONSTRAINT fk_reactions_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS read_positions (
    user_id BIGINT NOT NULL,
    channel_id BIGINT NOT NULL,
    last_read_message_id BIGINT NOT NULL,
    has_mention BOOLEAN NOT NULL DEFAULT FALSE,
    last_mention_message_id BIGINT NULL,
    updated_at DATETIME NOT NULL DEFAULT (UTC_TIMESTAMP()) ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, channel_id),
    INDEX idx_read_positions_channel_id (channel_id),
    CONSTRAINT fk_read_positions_channel FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
