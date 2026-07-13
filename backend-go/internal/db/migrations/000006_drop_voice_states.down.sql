-- Recreate voice_states table (unused by the Go backend; restored for rollback only).
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
