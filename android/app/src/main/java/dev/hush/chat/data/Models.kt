package dev.hush.chat.data

/**
 * Domain models.
 *
 * These are the only shapes the UI ever sees. Nothing above this layer knows
 * that a message was once ciphertext, or that a peer was once a JSON object
 * from the relay.
 */

/** A peer in the "All Users" list. */
data class Peer(
    val id: String,
    val displayName: String,
    val identityPublic: ByteArray,
    val safetyNumber: String,
    val verified: Boolean,
    val lastSeen: Long,
    val online: Boolean,
    val unreadCount: Int,
) {
    /** Two-letter monogram for the avatar. */
    val initials: String
        get() = displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }

    // No id-only equals/hashCode override. The roster is a StateFlow and the
    // UI diffs by equality, so an id-only comparison made a changed unread
    // count (or a new display name) compare equal to the old row: StateFlow
    // deduplicated the emission and the badge silently never updated.
}

/** How a message was delivered. */
enum class DeliveryState { PENDING, SENT, FAILED }

enum class MessageKind { TEXT, CALL_SIGNALING, SYSTEM, IMAGE }

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    val sentAt: Long,
    val state: DeliveryState,
    val kind: MessageKind,
    /** True when the local user removed this message for everyone. */
    val deletedForEveryone: Boolean,
    val attachmentLabel: String? = null,
    /**
     * Disappearing-message bookkeeping. All three default to "never":
     *
     * - [seenAt]: when this side saw it (read time for inbound, receipt time
     *   for outbound). 0 until seen.
     * - [expiresAt]: absolute epoch ms this row self-deletes, 0 for never.
     *   Set at insert for sent-based timers, at seen-time for seen-based ones.
     * - [expirySeenMs]: the after-seen duration, needed to compute [expiresAt]
     *   once [seenAt] is known. 0 when the message has no seen-based timer.
     * - [kept]: exempt from timer expiry. Only timer expiry — an explicit
     *   delete-for-everyone still tombstones a kept copy.
     */
    val seenAt: Long = 0L,
    val expiresAt: Long = 0L,
    val expirySeenMs: Long = 0L,
    val kept: Boolean = false,
    /**
     * How this row died. False = explicitly deleted for everyone (by either
     * side); true = its own timer ran out. The transcript renders them
     * differently — "deleted" vs "disappeared" — because a user who set OFF
     * and watches a received message vanish needs to see that the *sender's*
     * timer did it, not a bug.
     */
    val expired: Boolean = false,
) {
    val isOutgoing: Boolean get() = senderId == LOCAL_SENTINEL

    companion object {
        /** Sentinel distinguishing "sent by me" from "sent by this peer". */
        const val LOCAL_SENTINEL = "@me"
    }
}

data class Conversation(
    val peerId: String,
    val peerName: String,
    val lastMessagePreview: String,
    val lastMessageAt: Long,
    val unread: Int,
    val online: Boolean,
    val typing: Boolean,
)

/** One search hit, with enough context to render a result row. */
data class SearchHit(
    val messageId: String,
    val conversationId: String,
    val peerName: String,
    val body: String,
    val sentAt: Long,
)

/**
 * One unsent outgoing message: the offline outbox entry.
 *
 * Id plus content only — the timer policy is re-read at flush time, so
 * a message queued under one policy and flushed under another goes out under
 * the current one rather than failing on a stale encoding. Image rows carry
 * their JPEG bytes as base64 in [attachment]; text rows leave it null.
 */
data class OutboxItem(
    val id: String,
    val body: String,
    val kind: MessageKind = MessageKind.TEXT,
    val attachment: String? = null,
)

/**
 * A private group: client-side only, invisible to non-members and to the
 * relay. Membership is a local table; every coordination frame rides a 1:1
 * E2E channel, so no other database ever holds a trace of the group.
 */
data class GroupInfo(
    val id: String,
    val name: String,
    val adminId: String,
    val createdAt: Long,
    val memberIds: List<String> = emptyList(),
    val unreadCount: Int = 0,
) {
    /** Two-letter monogram, same rule as peers. */
    val initials: String
        get() = name.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "#" }

    // Deliberately NO id-only equals/hashCode override. It was here, and it
    // was a bug: `_groups.value = store.listGroups()` produced a list that
    // compared equal to the previous one whenever ids matched, StateFlow
    // deduplicated the emission, and recomposition never ran. The symptom was
    // a rename, a member count, or an admin badge that visibly refused to
    // update while the system notice for the same event rendered fine.
    // Structural equality is required for a state class the UI diffs.
}
