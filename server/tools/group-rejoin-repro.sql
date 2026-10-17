-- Gate 1 evidence: the "re-added member sees an invisible group" bug.
--
-- Reproduces the exact statements HushStore.upsertGroup/deleteGroup/listGroups
-- run, on real SQLite. Run with the platform-tools sqlite3 binary.
--
-- Expected BEFORE the fix: `visible` = 0  (tombstone survived the re-join)
-- Expected AFTER  the fix: `visible` = 1, name = 'trio'

DROP TABLE IF EXISTS groups;

-- Schema exactly as HushStore creates it (DB v5).
CREATE TABLE groups (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL DEFAULT '',
    admin_id   TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL DEFAULT 0,
    deleted    INTEGER NOT NULL DEFAULT 0,
    opened_at  INTEGER NOT NULL DEFAULT 0
);

-- Step 1: third creates the group.
INSERT INTO groups (id, name, admin_id, created_at, deleted) VALUES ('g_trio', 'Group', 'p_third', 100, 0);

-- Step 2: second is REMOVED -> wipeGroup flags a tombstone.
UPDATE groups SET deleted = 1 WHERE id = 'g_trio';

-- Step 3: third re-invites second -> upsertGroup.
--   BEFORE: only name/admin_id are updated, `deleted` stays 1  <-- THE BUG
--   AFTER : `deleted = 0` is included in the DO UPDATE clause.
INSERT INTO groups (id, name, admin_id, created_at, deleted) VALUES ('g_trio', 'trio', 'p_third', 100, 0)
  ON CONFLICT(id) DO UPDATE SET name = excluded.name, admin_id = excluded.admin_id, deleted = 0;

-- Step 4: what the roster shows -- every read filters deleted = 0.
.headers on
.mode list
SELECT 'visible_count' AS check_name,
       (SELECT COUNT(*) FROM groups WHERE deleted = 0) AS value;
SELECT 'name' AS check_name, name AS value FROM groups WHERE id = 'g_trio';