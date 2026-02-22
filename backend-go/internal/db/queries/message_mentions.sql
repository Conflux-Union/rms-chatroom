-- name: CreateMessageMention :exec
INSERT INTO message_mentions (message_id, user_id) VALUES (sqlc.arg(message_id), sqlc.arg(user_id));

-- name: ListMentionsByMessage :many
SELECT * FROM message_mentions WHERE message_id = sqlc.arg(message_id);

-- name: ListMentionsByUser :many
SELECT * FROM message_mentions WHERE user_id = sqlc.arg(user_id);

-- name: DeleteMentionsByMessage :exec
DELETE FROM message_mentions WHERE message_id = sqlc.arg(message_id);
