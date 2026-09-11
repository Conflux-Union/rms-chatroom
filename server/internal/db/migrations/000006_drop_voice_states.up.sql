-- Drop voice_states table.
-- The table was created in the initial migration but the Go backend never
-- used it: voice join/leave state is sourced from LiveKit participant lists,
-- and host_mode / screen_share locks are held in-memory. The table has been
-- dead since the Python-to-Go port.
DROP TABLE IF EXISTS voice_states;
