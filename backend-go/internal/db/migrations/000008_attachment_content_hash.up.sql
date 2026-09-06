ALTER TABLE attachments
    ADD COLUMN content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER size,
    ADD INDEX idx_attachments_content_hash (content_hash);
