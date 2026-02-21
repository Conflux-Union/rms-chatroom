-- name: CreateMuteRecord :execresult
INSERT INTO mute_records (user_id, scope, server_id, channel_id, muted_until, muted_by, reason)
VALUES (sqlc.arg(user_id), sqlc.arg(scope), sqlc.arg(server_id), sqlc.arg(channel_id), sqlc.arg(muted_until), sqlc.arg(muted_by), sqlc.arg(reason));

-- name: DeleteMuteRecord :exec
DELETE FROM mute_records WHERE id = sqlc.arg(id);

-- name: ListActiveMutesByUser :many
SELECT * FROM mute_records
WHERE user_id = sqlc.arg(user_id)
  AND (muted_until IS NULL OR muted_until > UTC_TIMESTAMP());
