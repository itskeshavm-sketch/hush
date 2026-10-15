package dev.hush.chat.data

import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny in-process bus for WebRTC signaling payloads that have already been
 * decrypted but are not chat messages.
 *
 * Signaling is not a conversation. SDP offers, answers and ICE candidates are
 * short-lived, per-call, and worthless once the call ends — routing them
 * through the message table would mean persisting ephemeral session state that
 * has no business surviving the call. The relay already gives them their own
 * frame type (`kind: "rtc"`), and this is where that distinction lands.
 */
object SignalingRoute {

    private val listeners = ConcurrentHashMap<String, MutableList<(String) -> Unit>>()

    /** Payload type constants, shared with the WebRTC layer. */
    const val TYPE_OFFER = "offer"
    const val TYPE_ANSWER = "answer"
    const val TYPE_ICE = "ice"
    const val TYPE_HANGUP = "hangup"
    const val TYPE_BUSY = "busy"

    /**
     * Register for signaling from [peerId]. The returned lambda must be invoked
     * to unsubscribe — leaking a listener here keeps a dead Activity's closure
     * alive for the process lifetime.
     */
    fun subscribe(peerId: String, handler: (String) -> Unit): () -> Unit {
        listeners.computeIfAbsent(peerId) { mutableListOf() }.add(handler)
        return {
            listeners[peerId]?.remove(handler)
            if (listeners[peerId]?.isEmpty() == true) listeners.remove(peerId)
        }
    }

    /** Deliver a decrypted signaling payload to whoever is waiting for it. */
    fun deliver(peerId: String, payload: String) {
        listeners[peerId]?.toList()?.forEach { handler ->
            runCatching { handler(payload) }
        }
    }

    /**
     * Send a signaling payload to a peer.
     *
     * Outbound signaling does not go through this bus — it is handed to the
     * transport, which encrypts and relays it. This exists so a caller that
     * only has a peer id can signal without holding a relay reference, and it
     * is a no-op when the remote side has no live subscription, which is the
     * correct behaviour: signaling is fire-and-forget and never queued.
     */
    fun emit(peerId: String, type: String, body: String) {
        deliver(peerId, listOf(type, body).joinToString("|"))
    }

    fun clear(peerId: String) {
        listeners.remove(peerId)
    }
}
