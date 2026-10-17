-- Two-device divergence simulation for group admin succession.
--
-- Each "device" is a SEPARATE database with its OWN clock. That is the whole
-- point: HUSH has no server time, so joined_at is stamped locally and is not
-- comparable across phones.
--
-- Rule under test (HushStore): admin = member with the smallest joined_at.
-- Path: setGroupMembers (creation, index-based) vs addGroupMember (add/remove
-- and re-add, wall-clock-based).

-- =====================================================================
-- DEVICE A  (phone 1). Clock is BEHIND: its "now" reads 900.
-- =====================================================================
DROP TABLE IF EXISTS groups_a;
CREATE TABLE groups_a (group_id TEXT, peer_id TEXT, joined_at INTEGER);
INSERT INTO groups_a VALUES ('g','p_first',  1000);   -- created first
INSERT INTO groups_a VALUES ('g','p_second', 1001);

-- =====================================================================
-- DEVICE B  (phone 2). Clock is AHEAD: its "now" reads 5000.
-- =====================================================================
DROP TABLE IF EXISTS groups_b;
CREATE TABLE groups_b (group_id TEXT, peer_id TEXT, joined_at INTEGER);
INSERT INTO groups_b VALUES ('g','p_first',  1000);
INSERT INTO groups_b VALUES ('g','p_second',1001);

-- Both devices remove p_second, then p_second is re-added by the admin.
DELETE FROM groups_a WHERE peer_id='p_second';
DELETE FROM groups_b WHERE peer_id='p_second';

-- Device A adds p_second using ITS clock (900). INSERT OR IGNORE, so a fresh
-- row is written because the old one was deleted.
INSERT OR IGNORE INTO groups_a VALUES ('g','p_second', 900);
-- Device B adds p_second using ITS clock (5000).
INSERT OR IGNORE INTO groups_b VALUES ('g','p_second', 5000);

.headers on
.mode list

SELECT '--- DEVICE A rows ---' AS x;
SELECT peer_id, joined_at FROM groups_a ORDER BY joined_at, peer_id;
SELECT '--- DEVICE B rows ---' AS x;
SELECT peer_id, joined_at FROM groups_b ORDER BY joined_at, peer_id;

-- Admin = smallest joined_at (HushStore.groupAdmin / groupInfo derivation).
SELECT '--- derived admin ---' AS x;
SELECT 'A' AS device, (SELECT peer_id FROM groups_a ORDER BY joined_at ASC, peer_id ASC LIMIT 1) AS admin
UNION ALL
SELECT 'B', (SELECT peer_id FROM groups_b ORDER BY joined_at ASC, peer_id ASC LIMIT 1);

-- Divergence check: 0 rows = agreement, 2 rows = the reported bug.
SELECT 'MISMATCH' AS divergence_check
WHERE (SELECT peer_id FROM groups_a ORDER BY joined_at ASC, peer_id ASC LIMIT 1)
   <> (SELECT peer_id FROM groups_b ORDER BY joined_at ASC, peer_id ASC LIMIT 1);