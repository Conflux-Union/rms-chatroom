-- name: GetMessage :one
SELECT * FROM messages WHERE id = sqlc.arg(id);

-- name: ListMessagesByChannel :many
SELECT * FROM messages
WHERE channel_id = sqlc.arg(channel_id) AND is_deleted = FALSE
  AND (sqlc.arg(before_id) = 0 OR id < sqlc.arg(before_id))
ORDER BY id DESC
LIMIT sqlc.arg(msg_limit);

-- name: CreateMessage :execresult
INSERT INTO messages (channel_id, user_id, username, content, reply_to_id)
VALUES (sqlc.arg(channel_id), sqlc.arg(user_id), sqlc.arg(username), sqlc.arg(content), sqlc.arg(reply_to_id));

-- name: UpdateMessageContent :exec
UPDATE messages SET content = sqlc.arg(content), edited_at = UTC_TIMESTAMP() WHERE id = sqlc.arg(id);

-- name: SoftDeleteMessage :exec
UPDATE messages SET is_deleted = TRUE, deleted_at = UTC_TIMESTAMP(), deleted_by = sqlc.arg(deleted_by) WHERE id = sqlc.arg(id);

-- name: GetChannelMembers :many
SELECT DISTINCT user_id, username FROM messages WHERE channel_id = sqlc.arg(channel_id) AND is_deleted = FALSE;
