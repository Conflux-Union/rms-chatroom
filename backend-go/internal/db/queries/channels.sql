-- name: GetChannel :one
SELECT * FROM channels WHERE id = sqlc.arg(id);

-- name: ListChannelsByServer :many
SELECT * FROM channels WHERE server_id = sqlc.arg(server_id) ORDER BY top_position, position;

-- name: ListChannelsByGroup :many
SELECT * FROM channels WHERE group_id = sqlc.arg(group_id) ORDER BY position;

-- name: ListUngroupedChannels :many
SELECT * FROM channels WHERE server_id = sqlc.arg(server_id) AND group_id IS NULL ORDER BY top_position;

-- name: CreateChannel :execresult
INSERT INTO channels (server_id, group_id, name, type, position, top_position,
    visibility_min_server_level, visibility_min_internal_level,
    speak_min_server_level, speak_min_internal_level)
VALUES (sqlc.arg(server_id), sqlc.arg(group_id), sqlc.arg(name), sqlc.arg(type),
    sqlc.arg(position), sqlc.arg(top_position),
    sqlc.arg(visibility_min_server_level), sqlc.arg(visibility_min_internal_level),
    sqlc.arg(speak_min_server_level), sqlc.arg(speak_min_internal_level));

-- name: UpdateChannel :exec
UPDATE channels
SET name = sqlc.arg(name), group_id = sqlc.arg(group_id),
    visibility_min_server_level = sqlc.arg(visibility_min_server_level),
    visibility_min_internal_level = sqlc.arg(visibility_min_internal_level),
    speak_min_server_level = sqlc.arg(speak_min_server_level),
    speak_min_internal_level = sqlc.arg(speak_min_internal_level)
WHERE id = sqlc.arg(id);

-- name: UpdateChannelPosition :exec
UPDATE channels SET position = sqlc.arg(position) WHERE id = sqlc.arg(id);

-- name: UpdateChannelTopPosition :exec
UPDATE channels SET top_position = sqlc.arg(top_position) WHERE id = sqlc.arg(id);

-- name: DeleteChannel :exec
DELETE FROM channels WHERE id = sqlc.arg(id);
