-- name: UpsertReadPosition :exec
INSERT INTO read_positions (user_id, channel_id, last_read_message_id, has_mention, last_mention_message_id)
VALUES (sqlc.arg(user_id), sqlc.arg(channel_id), sqlc.arg(last_read_message_id), sqlc.arg(has_mention), sqlc.arg(last_mention_message_id))
ON DUPLICATE KEY UPDATE
    last_read_message_id = VALUES(last_read_message_id),
    has_mention = VALUES(has_mention),
    last_mention_message_id = VALUES(last_mention_message_id),
    updated_at = UTC_TIMESTAMP();

-- name: ListReadPositionsByUser :many
SELECT * FROM read_positions WHERE user_id = sqlc.arg(user_id);
