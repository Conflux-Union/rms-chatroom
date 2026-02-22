-- name: GetServer :one
SELECT * FROM servers WHERE id = sqlc.arg(id);

-- name: ListServers :many
SELECT * FROM servers ORDER BY id;

-- name: CreateServer :execresult
INSERT INTO servers (name, icon, owner_id, min_server_level, min_internal_level)
VALUES (sqlc.arg(name), sqlc.arg(icon), sqlc.arg(owner_id), sqlc.arg(min_server_level), sqlc.arg(min_internal_level));

-- name: UpdateServer :exec
UPDATE servers
SET name = sqlc.arg(name), icon = sqlc.arg(icon),
    min_server_level = sqlc.arg(min_server_level),
    min_internal_level = sqlc.arg(min_internal_level)
WHERE id = sqlc.arg(id);

-- name: DeleteServer :exec
DELETE FROM servers WHERE id = sqlc.arg(id);
