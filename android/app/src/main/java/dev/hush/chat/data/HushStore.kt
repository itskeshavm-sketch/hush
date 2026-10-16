package dev.hush.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Local message store.
 *
 * Built on raw [SQLiteOpenHelper] rather than Room, deliberately. Room needs
 * KSP codegen, and codegen is the single most common way a clean-clone Android
 * build fails. The schema here is four tables and the queries are simple, so
 * the annotation processor buys nothing worth that risk.
 *
 * Search uses SQLite's FTS4, which ships inside Android. This matters for the
 * privacy story: search runs entirely on-device over the local database. There
 * is no search endpoint on the relay to subpoena, and typing a phrase into HUSH
 * search never puts it on a server.
 *
 * Threading: all access is funnelled through [ioLock]. SQLiteDatabase itself is
 * thread-safe, but read-modify-write sequences (for example, appending to a
 * message and then bumping an unread counter) are not, and interleaving them
 * silently loses counts.
 */
class HushStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val ioLock = ReentrantReadWriteLock()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL lets the UI read while a write is in flight. Without it, a search
        // typing fast enough to overlap an incoming message hits SQLITE_BUSY.
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE peers (
                id             TEXT PRIMARY KEY,
                display_name   TEXT NOT NULL,
                identity_pub   BLOB NOT NULL,
                safety_number  TEXT NOT NULL DEFAULT '',
                verified       INTEGER NOT NULL DEFAULT 0,
                last_seen      INTEGER NOT NULL DEFAULT 0,
                online         INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE messages (
                id               TEXT PRIMARY KEY,
                conversation_id  TEXT NOT NULL,
                sender_id        TEXT NOT NULL,
                sender_name      TEXT NOT NULL DEFAULT '',
                body             TEXT NOT NULL,
                sent_at          INTEGER NOT NULL,
                state            INTEGER NOT NULL,
                kind             INTEGER NOT NULL DEFAULT 0,
                deleted          INTEGER NOT NULL DEFAULT 0,
                attachment       TEXT,
                seen_at          INTEGER NOT NULL DEFAULT 0,
                expires_at       INTEGER NOT NULL DEFAULT 0,
                exp_seen_ms      INTEGER NOT NULL DEFAULT 0,
                kept             INTEGER NOT NULL DEFAULT 0,
                expired          INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // The two hot queries are "messages in a conversation, newest first"
        // and "unread count per conversation". Both are index-supported.
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conversation_id, sent_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_state ON messages(state, deleted)")

        // FTS4 mirrors the searchable text. It is populated by trigger so a
        // message can never be inserted without also being searchable — two
        // separate code paths would drift the first time someone forgot one.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE messages_fts USING fts4(
                body,
                tokenize=unicode61
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(docid, body) VALUES (new.rowid, new.body);
            END
            """.trimIndent()
        )

        // Deleting a message must delete its index row too, or search keeps
        // returning text the user can no longer see anywhere in the app.
        db.execSQL(
            """
            CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
                DELETE FROM messages_fts WHERE docid = old.rowid;
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER messages_au AFTER UPDATE OF body ON messages BEGIN
                DELETE FROM messages_fts WHERE docid = old.rowid;
                INSERT INTO messages_fts(docid, body) VALUES (new.rowid, new.body);
            END
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first release. Future migrations go here, one branch per
        // version, and each must preserve the FTS table's consistency with
        // `messages` — a half-migrated FTS index returns confidently wrong
        // search results, which is worse than no search.
        if (oldVersion < 1) onCreate(db)
        if (oldVersion < 2) {
            // Disappearing messages: seen tracking, deadlines, keep flags.
            // Each ALTER is guarded so a half-run upgrade retries cleanly
            // instead of dying on "duplicate column name".
            listOf(
                "ALTER TABLE messages ADD COLUMN seen_at INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE messages ADD COLUMN exp_seen_ms INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE messages ADD COLUMN kept INTEGER NOT NULL DEFAULT 0",
            ).forEach { runCatching { db.execSQL(it) } }
        }
        if (oldVersion < 3) {
            // Expired-vs-deleted distinction for the transcript. Old expired
            // rows keep expired = 0 and render as deleted — factually fine,
            // they were removed either way.
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN expired INTEGER NOT NULL DEFAULT 0") }
        }
    }

    // -------------------------------------------------------------------------
    // Peers
    // -------------------------------------------------------------------------

    suspend fun upsertPeers(peers: List<Peer>) = withContext(Dispatchers.IO) {
        if (peers.isEmpty()) return@withContext
        ioLock.write {
            val db = writableDatabase
            db.beginTransaction()
            try {
                peers.forEach { peer ->
                    val existing = db.rawQuery(
                        "SELECT verified, safety_number FROM peers WHERE id = ?",
                        arrayOf(peer.id),
                    ).use { c ->
                        if (c.moveToFirst()) c.getInt(0) to c.getString(1) else null
                    }

                    db.insertWithOnConflict(
                        "peers",
                        null,
                        ContentValues().apply {
                            put("id", peer.id)
                            put("display_name", peer.displayName)
                            put("identity_pub", peer.identityPublic)
                            // Verification is sticky. A roster refresh must
                            // never silently un-verify someone, or key changes
                            // would become a downgrade attack: an attacker
                            // reconnects unverified and the user is trained to
                            // ignore the warning.
                            put("verified", existing?.first ?: if (peer.verified) 1 else 0)
                            put("safety_number", existing?.second ?: peer.safetyNumber)
                            put("last_seen", peer.lastSeen)
                            put("online", if (peer.online) 1 else 0)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun setOnline(ids: List<String>, online: Boolean) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        ioLock.write {
            val db = writableDatabase
            db.beginTransaction()
            try {
                ids.forEach {
                    db.execSQL("UPDATE peers SET online = ?, last_seen = ? WHERE id IN (SELECT id FROM peers WHERE id = ?)",
                        arrayOf<Any>(if (online) 1 else 0, System.currentTimeMillis(), it))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun markVerified(peerId: String, verified: Boolean) = withContext(Dispatchers.IO) {
        ioLock.write {
            writableDatabase.execSQL(
                "UPDATE peers SET verified = ? WHERE id = ?",
                arrayOf<Any>(if (verified) 1 else 0, peerId),
            )
        }
    }

    /**
     * Reconcile the local peer table with a fresh roster.
     *
     * Peers the relay no longer reports are marked offline, never deleted.
     * That is what makes offline chat possible: you can open a conversation
     * with someone who is gone, write to them, and the message waits in the
     * local outbox until the relay reports them again. Deleting message-less
     * rows used to keep test probes from lingering, but it also deleted every
     * real peer the moment they disconnected — so a contact you had never
     * messaged vanished entirely instead of reading "offline".
     *
     * The tradeoff is accepted deliberately: a throwaway test name lingers as
     * an offline row until reinstall. A contact list that eats real people is
     * worse than one that keeps fake ones.
     *
     * No message content is retained by the relay for this. The queue lives in
     * this device's outbox; the relay still stores nothing.
     */
    suspend fun reconcilePeers(liveIds: List<String>) = withContext(Dispatchers.IO) {
        ioLock.write {
            val db = writableDatabase
            db.beginTransaction()
            try {
                if (liveIds.isEmpty()) {
                    db.execSQL("UPDATE peers SET online = 0")
                } else {
                    val placeholders = liveIds.joinToString(",") { "?" }
                    db.execSQL(
                        "UPDATE peers SET online = 0 WHERE id NOT IN ($placeholders)",
                        liveIds.toTypedArray(),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun loadPeers(): List<Peer> = withContext(Dispatchers.IO) {
        ioLock.read {
            readableDatabase.rawQuery(
                """
                SELECT p.id, p.display_name, p.identity_pub, p.safety_number, p.verified, p.last_seen, p.online,
                       (SELECT COUNT(*) FROM messages m
                         WHERE m.conversation_id = p.id AND m.sender_id != ?
                           AND m.deleted = 0 AND m.state != ?) AS unread
                FROM peers p
                ORDER BY p.online DESC, p.last_seen DESC, p.display_name ASC
                """.trimIndent(),
                arrayOf(Message.LOCAL_SENTINEL, DeliveryState.FAILED.ordinal.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            Peer(
                                id = c.getString(0),
                                displayName = c.getString(1),
                                identityPublic = c.getBlob(2) ?: ByteArray(0),
                                safetyNumber = c.getString(3) ?: "",
                                verified = c.getInt(4) == 1,
                                lastSeen = c.getLong(5),
                                online = c.getInt(6) == 1,
                                unreadCount = c.getInt(7),
                            )
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Messages
    // -------------------------------------------------------------------------

    suspend fun insertMessage(message: Message) = withContext(Dispatchers.IO) {
        ioLock.write {
            val db = writableDatabase
            db.insertWithOnConflict(
                "messages",
                null,
                ContentValues().apply {
                    put("id", message.id)
                    put("conversation_id", message.conversationId)
                    put("sender_id", message.senderId)
                    put("sender_name", message.senderName)
                    put("body", message.body)
                    put("sent_at", message.sentAt)
                    put("state", message.state.ordinal)
                    put("kind", message.kind.ordinal)
                    put("deleted", if (message.deletedForEveryone) 1 else 0)
                    message.attachmentLabel?.let { put("attachment", it) }
                    put("seen_at", message.seenAt)
                    put("expires_at", message.expiresAt)
                    put("exp_seen_ms", message.expirySeenMs)
                    put("kept", if (message.kept) 1 else 0)
                    put("expired", if (message.expired) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /**
     * Insert only when no row holds this id. For inbound messages addressed by
     * sender-stable id.
     *
     * Redelivery is normal here (offline flush, retry, reset healing all
     * re-send the same id), and REPLACE on a duplicate deletes + re-inserts:
     * new rowid, new receipt-time `sent_at`, recomputed expiry — the message
     * jumps to the bottom and its disappearing deadline extends. First
     * arrival wins instead: same content, original position, original
     * deadline. Own sends and system notices use fresh UUIDs and keep
     * [insertMessage]; only the two inbound sites (text, image assembly) come
     * here.
     */
    suspend fun insertMessageIfAbsent(message: Message): Boolean = withContext(Dispatchers.IO) {
        ioLock.write {
            val db = writableDatabase
            db.insertWithOnConflict(
                "messages",
                null,
                ContentValues().apply {
                    put("id", message.id)
                    put("conversation_id", message.conversationId)
                    put("sender_id", message.senderId)
                    put("sender_name", message.senderName)
                    put("body", message.body)
                    put("sent_at", message.sentAt)
                    put("state", message.state.ordinal)
                    put("kind", message.kind.ordinal)
                    put("deleted", if (message.deletedForEveryone) 1 else 0)
                    message.attachmentLabel?.let { put("attachment", it) }
                    put("seen_at", message.seenAt)
                    put("expires_at", message.expiresAt)
                    put("exp_seen_ms", message.expirySeenMs)
                    put("kept", if (message.kept) 1 else 0)
                    put("expired", if (message.expired) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        }
    }

    suspend fun updateState(messageId: String, state: DeliveryState) = withContext(Dispatchers.IO) {
        ioLock.write {
            writableDatabase.execSQL(
                "UPDATE messages SET state = ? WHERE id = ?",
                arrayOf<Any>(state.ordinal, messageId),
            )
        }
    }

    /**
     * Tombstone a message for both sides.
     *
     * The body is overwritten rather than the row deleted, because the FTS
     * trigger on DELETE removes the index entry anyway but a hard delete would
     * also destroy the rowid-to-message mapping that a recipient needs to
     * reconcile an inbound delete against a local copy. Blanking the text is
     * irreversible to search while keeping the row's identity.
     *
     * This is the EXPLICIT path: expired stays 0, so the transcript renders
     * "deleted". Timer expiry is the separate [expireDue] path, which marks
     * expired so the transcript renders "disappeared".
     */
    suspend fun markDeletedForEveryone(messageId: String) = withContext(Dispatchers.IO) {
        ioLock.write {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "UPDATE messages SET deleted = 1, body = '' WHERE id = ?",
                    arrayOf<Any>(messageId),
                )
                // The UPDATE trigger only fires on body changes, which it just
                // did, so the FTS row is removed here too.
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun loadMessages(conversationId: String, limit: Int = 500): List<Message> =
        withContext(Dispatchers.IO) {
            ioLock.read {
                readableDatabase.rawQuery(
                    """
                    SELECT id, conversation_id, sender_id, sender_name, body, sent_at, state, kind, deleted, attachment,
                           seen_at, expires_at, exp_seen_ms, kept, expired
                    FROM messages
                    WHERE conversation_id = ?
                    ORDER BY sent_at DESC, rowid DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(conversationId, limit.toString()),
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                Message(
                                    id = c.getString(0),
                                    conversationId = c.getString(1),
                                    senderId = c.getString(2),
                                    senderName = c.getString(3) ?: "",
                                    body = c.getString(4) ?: "",
                                    sentAt = c.getLong(5),
                                    state = DeliveryState.entries.getOrElse(c.getInt(6)) { DeliveryState.SENT },
                                    kind = MessageKind.entries.getOrElse(c.getInt(7)) { MessageKind.TEXT },
                                    deletedForEveryone = c.getInt(8) == 1,
                                    attachmentLabel = c.getString(9),
                                    seenAt = c.getLong(10),
                                    expiresAt = c.getLong(11),
                                    expirySeenMs = c.getLong(12),
                                    kept = c.getInt(13) == 1,
                                    expired = c.getInt(14) == 1,
                                )
                            )
                        }
                    }.reversed() // oldest first for the transcript
                }
            }
        }

    // -------------------------------------------------------------------------
    // Disappearing messages
    // -------------------------------------------------------------------------

    /**
     * Tombstone everything whose deadline has passed. Keep-exempt rows survive;
     * an explicit delete-for-everyone is a separate path that ignores [kept].
     *
     * Expired rows are marked expired = 1 (explicit deletes leave it 0), so
     * the transcript can say "disappeared" instead of "deleted".
     *
     * The body-blanking reuses the same UPDATE trigger as manual deletes, so
     * expired text leaves search exactly the way a deleted message does.
     *
     * @return how many rows were tombstoned, so the caller knows whether any
     *   open transcript needs a reload.
     */
    suspend fun expireDue(nowMs: Long): Int = withContext(Dispatchers.IO) {
        ioLock.write {
            var count = 0
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE deleted = 0 AND kept = 0 AND expires_at > 0 AND expires_at <= ?",
                arrayOf(nowMs.toString()),
            ).use { c -> if (c.moveToFirst()) count = c.getInt(0) }
            if (count > 0) {
                writableDatabase.execSQL(
                    "UPDATE messages SET deleted = 1, expired = 1, body = '' WHERE deleted = 0 AND kept = 0 AND expires_at > 0 AND expires_at <= ?",
                    arrayOf<Any>(nowMs),
                )
            }
            count
        }
    }

    /**
     * A read receipt arrived for one of our sent messages: record when the
     * peer saw it, and arm the deadline if it carries an after-seen timer.
     *
     * First receipt wins (`seen_at = 0` guard). Receipts are re-sent as
     * duplicates can arrive — the receiver retries them to heal a dropped
     * frame — and every re-send must not push our deadline further out, or a
     * "2 min after seen" message would live as long as duplicates keep
     * arriving. The deadline anchors at the first time the peer saw it.
     */
    suspend fun markPeerSeen(messageId: String, seenAtMs: Long) = withContext(Dispatchers.IO) {
        ioLock.write {
            writableDatabase.execSQL(
                """
                UPDATE messages
                SET seen_at = ?,
                    expires_at = CASE WHEN exp_seen_ms > 0 THEN ? + exp_seen_ms ELSE expires_at END
                WHERE id = ? AND sender_id = ? AND seen_at = 0
                """.trimIndent(),
                arrayOf<Any>(seenAtMs, seenAtMs, messageId, Message.LOCAL_SENTINEL),
            )
        }
    }

    /**
     * We displayed inbound messages: record local seen time and arm their
     * after-seen deadlines. Only rows that actually carry a seen-based timer
     * and have never been seen — everything else is a wasted write.
     */
    suspend fun markLocalSeen(conversationId: String, seenAtMs: Long) = withContext(Dispatchers.IO) {
        ioLock.write {
            writableDatabase.execSQL(
                """
                UPDATE messages
                SET seen_at = ?, expires_at = ? + exp_seen_ms
                WHERE conversation_id = ? AND sender_id != ?
                  AND deleted = 0 AND seen_at = 0 AND exp_seen_ms > 0
                """.trimIndent(),
                arrayOf<Any>(seenAtMs, seenAtMs, conversationId, Message.LOCAL_SENTINEL),
            )
        }
    }

    /** Inbound expiring messages we have never shown, oldest first, capped. */
    suspend fun unseenInboundIds(conversationId: String, limit: Int = 100): List<String> =
        withContext(Dispatchers.IO) {
            ioLock.read {
                readableDatabase.rawQuery(
                    """
                    SELECT id FROM messages
                    WHERE conversation_id = ? AND sender_id != ?
                      AND deleted = 0 AND seen_at = 0 AND exp_seen_ms > 0
                    ORDER BY sent_at ASC, rowid ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(conversationId, Message.LOCAL_SENTINEL, limit.toString()),
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(c.getString(0))
                    }
                }
            }
        }

    /** Exempt a message from timer expiry. Explicit deletes still apply. */
    suspend fun setKept(messageId: String, kept: Boolean) = withContext(Dispatchers.IO) {
        ioLock.write {
            writableDatabase.execSQL(
                "UPDATE messages SET kept = ? WHERE id = ?",
                arrayOf<Any>(if (kept) 1 else 0, messageId),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Offline outbox
    // -------------------------------------------------------------------------

    /**
     * Unsent outgoing messages for one conversation, oldest first.
     *
     * These are the rows a send attempt left behind as PENDING or FAILED —
     * the peer was offline, we were offline, or the bundle had not arrived
     * yet. They are the offline queue: the relay stores nothing for absent
     * peers, so the sender's device holds them and flushes them when the peer
     * reappears. Tombstoned rows are excluded — a message whose own timer
     * expired before delivery must never be delivered late.
     */
    suspend fun pendingOutgoing(conversationId: String, limit: Int = 100): List<OutboxItem> =
        withContext(Dispatchers.IO) {
            ioLock.read {
                readableDatabase.rawQuery(
                    """
                    SELECT id, body, kind, attachment FROM messages
                    WHERE conversation_id = ? AND sender_id = ?
                      AND deleted = 0 AND state != ?
                    ORDER BY sent_at ASC, rowid ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(
                        conversationId,
                        Message.LOCAL_SENTINEL,
                        DeliveryState.SENT.ordinal.toString(),
                        limit.toString(),
                    ),
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            OutboxItem(
                                id = c.getString(0),
                                body = c.getString(1) ?: "",
                                kind = MessageKind.entries.getOrElse(c.getInt(2)) { MessageKind.TEXT },
                                attachment = c.getString(3),
                            )
                        )
                    }
                }
            }
        }

    /** True when this conversation holds unsent outgoing rows. Cheap gate for flush. */
    suspend fun hasPending(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        ioLock.read {
            readableDatabase.rawQuery(
                """
                SELECT 1 FROM messages
                WHERE conversation_id = ? AND sender_id = ?
                  AND deleted = 0 AND state != ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    conversationId,
                    Message.LOCAL_SENTINEL,
                    DeliveryState.SENT.ordinal.toString(),
                ),
            ).use { c -> c.moveToFirst() }
        }
    }

    /**
     * Move recently-sent rows back to the outbox for re-delivery.
     *
     * Used when a peer reports they cannot decrypt us: everything we sent
     * recently went out on a stale session — relay-accepted but unreadable,
     * so the rows sit at SENT while the peer saw nothing. Re-queueing them
     * under the fresh session (same stable ids, so the receiver's replace is
     * idempotent) restores what would otherwise be a silent hole.
     *
     * Windowed, deliberately: re-sending the whole history would overwrite
     * the receiver's seen/kept state via CONFLICT_REPLACE. Recent messages
     * are the desync case (reinstall-then-chat); older ones stay as-is.
     */
    suspend fun demoteRecent(conversationId: String, sinceMs: Long) = withContext(Dispatchers.IO) {
        ioLock.write {
            writableDatabase.execSQL(
                """
                UPDATE messages SET state = ?
                WHERE conversation_id = ? AND sender_id = ?
                  AND deleted = 0 AND state = ? AND sent_at >= ?
                """.trimIndent(),
                arrayOf<Any>(
                    DeliveryState.PENDING.ordinal,
                    conversationId,
                    Message.LOCAL_SENTINEL,
                    DeliveryState.SENT.ordinal,
                    sinceMs,
                ),
            )
        }
    }
    /**
     * Locally-seen inbound timer messages for receipt re-sends.
     * @param sinceMs only rows seen at or after this time; 0 means all.
     */
    suspend fun seenTimerIds(conversationId: String, sinceMs: Long = 0L, limit: Int = 100): List<String> =
        withContext(Dispatchers.IO) {
            ioLock.read {
                readableDatabase.rawQuery(
                    """
                    SELECT id FROM messages
                    WHERE conversation_id = ? AND sender_id != ?
                      AND deleted = 0 AND exp_seen_ms > 0 AND seen_at >= ?
                    ORDER BY seen_at DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(conversationId, Message.LOCAL_SENTINEL, sinceMs.toString(), limit.toString()),
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(c.getString(0))
                    }
                }
            }
        }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Full-text search across every conversation.
     *
     * The query is passed as a bound parameter and wrapped in FTS4 phrase
     * syntax, so a user typing `" OR 1=1` searches for that literal text
     * rather than being able to append an FTS operator and read the whole
     * history through a crafted query.
     */
    suspend fun search(rawQuery: String, limit: Int = 100): List<SearchHit> = withContext(Dispatchers.IO) {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        // Escape the FTS4 metacharacter set so the input is always a phrase.
        val phrase = trimmed.replace("\"", "\"\"")

        ioLock.read {
            readableDatabase.rawQuery(
                """
                SELECT m.id, m.conversation_id, m.body, m.sent_at,
                       COALESCE(p.display_name, m.sender_name)
                FROM messages_fts f
                JOIN messages m ON m.rowid = f.docid
                LEFT JOIN peers  p ON p.id = m.conversation_id
                WHERE messages_fts MATCH ?
                  AND m.deleted = 0
                ORDER BY m.sent_at DESC, m.rowid DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf("\"$phrase\"", limit.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            SearchHit(
                                messageId = c.getString(0),
                                conversationId = c.getString(1),
                                peerName = c.getString(4) ?: "Unknown",
                                body = c.getString(2) ?: "",
                                sentAt = c.getLong(3),
                            )
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    /** Wipe everything. Used by "delete my identity" in Settings. */
    suspend fun nuke() = withContext(Dispatchers.IO) {
        ioLock.write {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM messages")
                db.execSQL("DELETE FROM messages_fts")
                db.execSQL("DELETE FROM peers")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    companion object {
        const val DB_NAME = "hush.db"
        const val DB_VERSION = 3
    }
}
