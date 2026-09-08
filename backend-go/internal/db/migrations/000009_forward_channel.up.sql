-- FORWARD channel type: bot-driven message sync (QQ / Minecraft events)

ALTER TABLE channels MODIFY `type` ENUM('TEXT', 'VOICE', 'FORWARD') NOT NULL DEFAULT 'TEXT';

ALTER TABLE messages
  ADD COLUMN source_platform VARCHAR(16) NULL,
  ADD COLUMN source_message_id VARCHAR(64) NULL,
  ADD COLUMN forward_meta JSON NULL,
  ADD INDEX ix_messages_source (source_platform, source_message_id);
