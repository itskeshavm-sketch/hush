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

    override fun equals(other: Any?): Boolean = other is Peer && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** How a message was delivered. */
enum class DeliveryState { PENDING, SENT, FAILED }

enum class MessageKind { TEXT, CALL_SIGNALING, SYSTEM }

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
