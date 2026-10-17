-- Phase 3 verification: the SAME two-device scenario as admin-skew-sim.sql,
-- now using the shipped counter-based rule instead of wall-clock stamps.
--
-- Devices still have wildly different clocks (900 and 5000). They no longer
-- matter, because joined_at is a counter derived from local membership, not a
-- time read from the device.
--
-- Expected: no MISMATCH row -- both devices elect the same admin.

DROP TABLE IF EXISTS groups_a;
CREATE TABLE groups_a (group_id TEXT, peer_id TEXT, joined_at INTEGER);
DROP TABLE IF EXISTS groups_b;
CREATE TABLE groups_b (group_id TEXT, peer_id TEXT, joined_at INTEGER);

-- Creation: setGroupMembers seeds counters 0,1 in list order on BOTH devices
-- (the invite carries the inviter's oldest-first ordering).
INSERT INTO groups_a VALUES ('g','p_first', 0), ('g','p_second', 1);
INSERT INTO groups_b VALUES ('g','p_first', 0), ('g','p_second', 1);

-- p_second removed, then re-added. addGroupMember uses nextJoinCounter =
-- MAX(joined_at)+1, computed locally. Device A's stale 900 clock is never read.
DELETE FROM groups_a WHERE peer_id='p_second';
DELETE FROM groups_b WHERE peer_id='p_second';

INSERT OR IGNORE INTO groups_a
  SELECT 'g','p_second',(SELECT COALESCE(MAX(joined_at),-1)+1 FROM groups_a WHERE group_id='g');
INSERT OR IGNORE INTO groups_b
  SELECT 'g','p_second',(SELECT COALESCE(MAX(joined_at),-1)+1 FROM groups_b WHERE group_id='g');

.headers on
.mode list

SELECT '--- DEVICE A rows (clock irrelevant) ---' AS x;
SELECT peer_id, joined_at FROM groups_a ORDER BY joined_at ASC, peer_id ASC;
SELECT '--- DEVICE B rows (clock irrelevant) ---' AS x;
SELECT peer_id, joined_at FROM groups_b ORDER BY joined_at ASC, peer_id ASC;

SELECT '--- derived admin ---' AS x;
SELECT 'A' AS device, (SELECT peer_id FROM groups_a ORDER BY joined_at ASC, peer_id ASC LIMIT 1) AS admin
UNION ALL
SELECT 'B', (SELECT peer_id FROM groups_b ORDER BY joined_at ASC, peer_id ASC LIMIT 1);

-- Zero rows here means the two devices AGREE.
SELECT 'MISMATCH' AS divergence_check
WHERE (SELECT peer_id FROM groups_a ORDER BY joined_at ASC, peer_id ASC LIMIT 1)
   <> (SELECT peer_id FROM groups_b ORDER BY joined_at ASC, peer_id ASC LIMIT 1);

SELECT 'AGREE' AS verdict
WHERE (SELECT peer_id FROM groups_a ORDER BY joined_at ASC, peer_id ASC LIMIT 1)
   = (SELECT peer_id FROM groups_b ORDER BY joined_at ASC, peer_id ASC LIMIT 1);