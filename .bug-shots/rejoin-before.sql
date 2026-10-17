-- BEFORE-FIX variant of HushStore.upsertGroup: the DO UPDATE clause updates
-- only `name` and `admin_id`, leaving the `deleted = 1` tombstone in place.
-- Every read of a group filters `deleted = 0`, so after this statement the
-- rejoined member's roster contains no group -- while the "You were added"
-- toast has already fired.
--
-- Expected result: visible_count = 0  (BUG REPRODUCED)

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
  ON CONFLICT(id) DO UPDATE SET name = excluded.name, admin_id = excluded.admin_id;

.headers on
.mode list
SELECT 'visible_count' AS check_name, (SELECT COUNT(*) FROM groups WHERE deleted = 0) AS value;
SELECT 'tombstone_left' AS check_name, deleted AS value FROM groups WHERE id = 'g_trio';