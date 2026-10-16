# Private groups + roster search — build plan (crucible)

Locked spec: pairwise fan-out over existing 1:1 sessions (zero relay/protocol
changes on the wire framing — new JSON payload versions only), max 50 members,
creator admin, admin passes to longest-standing member, shared name synced,
removed phone wipes group + history (others keep + notice), joiners get last 20
from the adder, v1 = text + timers + offline queue + delete-for-everyone (no
group images), auto-join with system notice, roster gets username search +
groups section. Both branches. All members must run this build (old builds
render v4/v5 frames as raw-text bubbles — same standing rule as always).

## Wire (no relay change; relay still sees only 1:1 envelopes)

- v4 group-text: {"v":4,"id","body","g":groupId,"ss","sn"} → Text with
  conversationId=groupId. Same stable-id/delete/timer semantics as v2.
- v5 control: {"v":5,"op","g",...} ops: invite (meta + members[] + history[]),
  add, remove, leave, rename. Accepted only from current admin (except leave,
  which any member may send for self). Interpret → GroupControl result.
- History items: {id,body,from,fromName,at,ss,sn} re-sealed by adder under
  ORIGINAL ids so future deletes match everywhere.

## Store (DB v4: new tables only, no migration of old rows)

- groups(id PK, name, admin_id, created_at, deleted)
- group_members(group_id, peer_id, joined_at) PK(group_id, peer_id)
- group_pending(message_id, member_id) PK both — per-member offline queue.
- Messages table reused verbatim (conversation_id = group id). pendingOutgoing
  EXCLUDES group conversations (routed per-member instead).
- Deterministic admin: earliest joined_at; ties → lowest peer id. Every member
  computes identically — no consensus protocol.

## ViewModel

- createGroup(name, memberIds) → local rows + invite to each (with last-20).
- sendGroupText: store once (SENT-optimistic per existing rule), fan out per
  member; unknown_peer errors → group_pending rows (existing demote path).
- syncRoster extension: flush group_pending for returning members.
- Inbound v4 → group transcript (+ sender names); v5 → apply membership ops
  (admin check), SYSTEM rows for notices, wipe+leave on remove.
- Group open → seen receipts to distinct senders (new query).
- Group delete-for-everyone → fan-out deletes.
- Expiry sweep already global — covers groups free.

## UI

- RosterScreen: username filter field at top; Groups section; New-group row.
- CreateGroupScreen: member search + multi-select, name field, 50-cap.
- ChatScreen: group mode — sender labels on incoming, member-count subtitle,
  info entry (members, add/remove for admin, rename, leave).
- Sender-name labels double as the anti-spoof surface (names are labels;
  safety numbers still per 1:1 session).

## Test

- Unit: v4/v5 seal+interpret round-trip, bounds rejection, admin rule,
  history cap. Full suite must stay 18/18+.
- assembleDebug both branches; dex-verify group symbols in both APKs.

## Out of scope (recorded)

Group images, accept/decline invites (auto-join v1), per-member read receipts
(first-seen only), admin transfer veto, group calls.
