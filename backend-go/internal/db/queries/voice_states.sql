-- name: UpsertVoiceState :exec
INSERT INTO voice_states (channel_id, user_id, username, muted, deafened)
VALUES (sqlc.arg(channel_id), sqlc.arg(user_id), sqlc.arg(username), sqlc.arg(muted), sqlc.arg(deafened))
ON DUPLICATE KEY UPDATE username = VALUES(username), muted = VALUES(muted), deafened = VALUES(deafened), joined_at = UTC_TIMESTAMP();

-- name: DeleteVoiceState :exec
DELETE FROM voice_states WHERE channel_id = sqlc.arg(channel_id) AND user_id = sqlc.arg(user_id);

-- name: ListVoiceStatesByChannel :many
SELECT * FROM voice_states WHERE channel_id = sqlc.arg(channel_id) ORDER BY joined_at;

-- name: DeleteAllVoiceStatesByChannel :exec
DELETE FROM voice_states WHERE channel_id = sqlc.arg(channel_id);
