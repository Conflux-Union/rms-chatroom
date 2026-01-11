# TODO List

## High Priority

(No pending items)

## Completed

- [x] Implement database auto-migration functionality
  - Integrated Alembic for database migrations
  - Detect missing tables and columns
  - Automatically add `channel_groups` table if not exists
  - Automatically add `channels.group_id` column if not exists
  - Handle schema changes gracefully without breaking existing deployments
  - Support both SQLite and MySQL dialects
  - Migrations run automatically on app startup

- [x] Fix deploy.py build command error (should run build:web from project root)
- [x] Fix API breaking change (PUT → PATCH, added backward compatibility)
- [x] Add reorder_mixed_list input validation
- [x] Fix ChannelList animation max-height hardcoded limit
