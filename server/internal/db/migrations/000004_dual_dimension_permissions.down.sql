ALTER TABLE channels DROP COLUMN speak_logic_operator;
ALTER TABLE channels DROP COLUMN speak_perm_min_level;
ALTER TABLE channels DROP COLUMN logic_operator;
ALTER TABLE channels DROP COLUMN perm_min_level;

ALTER TABLE channel_groups DROP COLUMN logic_operator;
ALTER TABLE channel_groups DROP COLUMN perm_min_level;

ALTER TABLE servers DROP COLUMN logic_operator;
ALTER TABLE servers DROP COLUMN perm_min_level;
