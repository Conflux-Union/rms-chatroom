-- name: AddReaction :exec
INSERT IGNORE INTO reactions (message_id, user_id, username, emoji)
VALUES (sqlc.arg(message_id), sqlc.arg(user_id), sqlc.arg(username), sqlc.arg(emoji));

-- name: RemoveReaction :exec
DELETE FROM reactions WHERE message_id = sqlc.arg(message_id) AND user_id = sqlc.arg(user_id) AND emoji = sqlc.arg(emoji);

-- name: ListReactionsByMessage :many
SELECT * FROM reactions WHERE message_id = sqlc.arg(message_id) ORDER BY created_at;
