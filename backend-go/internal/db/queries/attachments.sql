-- name: GetAttachment :one
SELECT * FROM attachments WHERE id = sqlc.arg(id);

-- name: CreateAttachment :execresult
INSERT INTO attachments (message_id, channel_id, user_id, filename, stored_name, content_type, size)
VALUES (sqlc.arg(message_id), sqlc.arg(channel_id), sqlc.arg(user_id), sqlc.arg(filename), sqlc.arg(stored_name), sqlc.arg(content_type), sqlc.arg(size));

-- name: ListUnlinkedAttachments :many
SELECT * FROM attachments WHERE channel_id = sqlc.arg(channel_id) AND user_id = sqlc.arg(user_id) AND message_id IS NULL;

-- name: LinkAttachmentToMessage :exec
UPDATE attachments SET message_id = sqlc.arg(message_id) WHERE id = sqlc.arg(id);

-- name: DeleteAttachment :exec
DELETE FROM attachments WHERE id = sqlc.arg(id);
