ALTER TABLE attachments
    DROP INDEX idx_attachments_content_hash,
    DROP COLUMN content_hash;
