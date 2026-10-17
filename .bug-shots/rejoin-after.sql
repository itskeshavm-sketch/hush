-- AFTER-FIX variant: identical sequence, with `deleted = 0` added to the
-- DO UPDATE clause (the shipped HushStore.upsertGroup).
--
-- Expected result: visible_count = 1, name = 'trio'  (BUG GONE)

DROP TABLE IF EXISTS groups;

CREATE TABLE groups (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL DEFAULT '',
    admin_id   TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL DEFAULT 0,
    deleted    INTEGER NOT NULL DEFAULT 0,
    opened_at  INTEGER NOT NULL DEFAULT 0
);

INSERT INTO groups (id, name, admin_id, created_at, deleted) VALUES ('g_trio', 'Group', 'p_third', 100, 0);
UPDATE groups SET deleted = 1 WHERE id = 'g_trio';

INSERT INTO groups (id, name, admin_id, created_at, deleted) VALUES ('g_trio', 'trio', 'p_third', 100, 0)
  ON CONFLICT(id) DO UPDATE SET name = excluded.name, admin_id = excluded.admin_id, deleted = 0;

.headers on
.mode list
SELECT 'visible_count' AS check_name, (SELECT COUNT(*) FROM groups WHERE deleted = 0) AS value;
SELECT 'tombstone_left' AS check_name, deleted AS value FROM groups WHERE id = 'g_trio';