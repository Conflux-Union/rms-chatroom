-- name: CreateVoiceInvite :execresult
INSERT INTO voice_invites (channel_id, token, created_by) VALUES (sqlc.arg(channel_id), sqlc.arg(token), sqlc.arg(created_by));

-- name: GetVoiceInviteByToken :one
SELECT * FROM voice_invites WHERE token = sqlc.arg(token);

-- name: MarkVoiceInviteUsed :exec
UPDATE voice_invites SET used = TRUE, used_by_name = sqlc.arg(used_by_name), used_at = UTC_TIMESTAMP() WHERE id = sqlc.arg(id);
