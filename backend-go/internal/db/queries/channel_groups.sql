-- name: GetChannelGroup :one
SELECT * FROM channel_groups WHERE id = sqlc.arg(id);

-- name: ListChannelGroupsByServer :many
SELECT * FROM channel_groups WHERE server_id = sqlc.arg(server_id) ORDER BY position;

-- name: CreateChannelGroup :execresult
INSERT INTO channel_groups (server_id, name, position, min_server_level, min_internal_level)
VALUES (sqlc.arg(server_id), sqlc.arg(name), sqlc.arg(position), sqlc.arg(min_server_level), sqlc.arg(min_internal_level));

-- name: UpdateChannelGroup :exec
UPDATE channel_groups
SET name = sqlc.arg(name),
    min_server_level = sqlc.arg(min_server_level),
    min_internal_level = sqlc.arg(min_internal_level)
WHERE id = sqlc.arg(id);

-- name: UpdateChannelGroupPosition :exec
UPDATE channel_groups SET position = sqlc.arg(position) WHERE id = sqlc.arg(id);

-- name: DeleteChannelGroup :exec
DELETE FROM channel_groups WHERE id = sqlc.arg(id);
