-- Reverse FORWARD channel type

ALTER TABLE messages
  DROP INDEX ix_messages_source,
  DROP COLUMN forward_meta,
  DROP COLUMN source_message_id,
  DROP COLUMN source_platform;

-- Reset FORWARD rows before shrinking the enum, otherwise the MODIFY
-- fails in strict mode (or truncates with a warning in non-strict mode).
UPDATE channels SET type = 'TEXT' WHERE type = 'FORWARD';
ALTER TABLE channels MODIFY `type` ENUM('TEXT', 'VOICE') NOT NULL DEFAULT 'TEXT';
