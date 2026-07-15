CREATE TABLE IF NOT EXISTS client_telemetry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    platform VARCHAR(16) NOT NULL,
    app_version VARCHAR(64) NOT NULL DEFAULT '',
    event_type VARCHAR(32) NOT NULL,
    message VARCHAR(2048) NOT NULL DEFAULT '',
    stack TEXT NULL,
    meta JSON NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_client_telemetry_type_time (event_type, created_at),
    INDEX idx_client_telemetry_platform_time (platform, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
