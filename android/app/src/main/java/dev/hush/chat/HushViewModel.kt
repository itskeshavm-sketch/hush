package dev.hush.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hush.chat.data.DeliveryState
import dev.hush.chat.data.HushStore
import dev.hush.chat.data.Message
import dev.hush.chat.data.MessageKind
import dev.hush.chat.data.Peer
import dev.hush.chat.data.SearchHit
import dev.hush.chat.data.SessionManager
import dev.hush.chat.data.SignalingRoute
import dev.hush.chat.net.RelayClient
import dev.hush.chat.net.RelayState
import dev.hush.chat.net.TlsPinner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Which screen the app is on. */
sealed interface Screen {
    data object Onboarding : Screen
    data object Roster : Screen
    data class Chat(val peerId: String, val peerName: String) : Screen
    data object Search : Screen
    data object Settings : Screen
    data class SafetyNumber(val peerId: String, val peerName: String) : Screen
    data class PeerInfo(val peerId: String, val peerName: String) : Screen
}

/** One-shot user-facing error. */
data class Toast(val text: String, val id: Long = System.nanoTime())

/**
 * The single orchestrator.
 *
 * Deliberately one ViewModel rather than one per screen: a chat app's state is
 * almost entirely shared (the roster, the socket, the ratchet sessions), and
 * splitting it across ViewModels means the socket outlives whichever screen
 * started it.
 */
class HushViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HushStore(app)
    private val sessions = SessionManager(app)
    private val relay = RelayClient(viewModelScope)

    private val prefs = app.getSharedPreferences("hush_prefs", Application.MODE_PRIVATE)

    private val _screen = MutableStateFlow<Screen>(Screen.Onboarding)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private val _typingIn = MutableStateFlow<Set<String>>(emptySet())
    val typingIn: StateFlow<Set<String>> = _typingIn.asStateFlow()

    private val _callActive = MutableStateFlow<String?>(null)
    val callActive: StateFlow<String?> = _callActive.asStateFlow()

    val relayState: StateFlow<RelayState> = relay.state
    val pinnedFingerprint: StateFlow<String?> = relay.pinnedFingerprint

    val displayName: String get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
    val relayUrl: String get() = prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY) ?: DEFAULT_RELAY

    private var joinLoop: Job? = null
    private var typingResetJob: Job? = null
    private var typingSentAt = 0L

    init {
        sessions.loadOrCreateIdentity()
        if (displayName.isNotEmpty()) {
            _screen.value = Screen.Roster
            startRelay()
        }
    }

    // -------------------------------------------------------------------------
    // Onboarding
    // -------------------------------------------------------------------------

    /**
     * There is no sign-up beyond a display name. That is the entire flow by
     * design: HUSH has no server-side account, so there is nothing to register
     * for and no credential to leak.
     *
     * The relay is set here too rather than only in Settings, because the
     * shipped default is a placeholder that cannot resolve — a new install
     * with no way to fix that before connecting is a dead app.
     */
    fun completeOnboarding(name: String, relay: String = relayUrl) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            emitToast("Pick a display name")
            return
        }
        val url = relay.trim()
        if (!url.startsWith("wss://")) {
            emitToast("Relay must be a wss:// address")
            return
        }

        prefs.edit()
            .putString(KEY_DISPLAY_NAME, trimmed)
            .putString(KEY_RELAY_URL, url)
            .apply()

        _screen.value = Screen.Roster
        startRelay()
    }

    fun changeDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
        startRelay() // re-announce under the new name
    }

    fun setRelayUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("wss://")) {
            emitToast("Relay must be a wss:// URL")
            return
        }
        prefs.edit().putString(KEY_RELAY_URL, trimmed).apply()
        startRelay()
    }

    // -------------------------------------------------------------------------
    // Relay
    // -------------------------------------------------------------------------

    private fun startRelay() {
        val hello = sessions.buildHello(displayName, prefs.getLong(KEY_JOINED_AT, 0L))
        val pin = prefs.getString(KEY_RELAY_PIN, null)

        // Persist the first observed certificate. Without this, every launch
        // re-runs first-use trust and a pinned relay is really just a trusted
        // one — which is the exact failure pinning exists to prevent.
        relay.onPinObserved = { fp ->
            prefs.edit().putString(KEY_RELAY_PIN, fp).apply()
        }

        relay.connect(relayUrl, pin, hello)
        observeFrames()
    }

    /**
     * Re-baseline the relay certificate.
     *
     * Exposed as an explicit, user-initiated action that shows the new
     * fingerprint. Silently accepting a changed certificate would make the
     * pinning worthless, so this is deliberately a two-step, visible process.
     */
    fun trustNewRelayCertificate(fingerprintHex: String) {
        prefs.edit().putString(KEY_RELAY_PIN, fingerprintHex).apply()
        relay.updatePin(fingerprintHex)
        emitToast("Relay certificate pinned")
    }

    private var observing = false

    private fun observeFrames() {
        if (observing) return
        observing = true

        viewModelScope.launch {
            relay.roster.collect { roster ->
                syncRoster(roster)
            }
        }

        viewModelScope.launch {
            relay.frames.collect { frame ->
                when (frame.optString("t")) {
                    "welcome" -> {
                        val you = frame.optJSONObject("you")
                        sessions.noteLocalPeerId(you?.optString("id").orEmpty())
                        if (prefs.getLong(KEY_JOINED_AT, 0L) == 0L) {
                            prefs.edit().putLong(KEY_JOINED_AT, System.currentTimeMillis()).apply()
                        }
                    }
                    "msg" -> handleInboundMessage(frame)
                    "delete" -> handleInboundDelete(frame)
                    "typing" -> handleInboundTyping(frame)
                    "error" -> emitToast(frame.optString("message").ifEmpty { "relay error" })
                }
            }
        }
    }

    private suspend fun syncRoster(incoming: List<dev.hush.chat.net.PeerInfo>) {
        val self = sessions.localPeerId()
        val others = incoming.filter { it.id != self }

        store.upsertPeers(
            others.map { info ->
                Peer(
                    id = info.id,
                    displayName = info.name,
                    identityPublic = info.identityPublic,
                    safetyNumber = sessions.safetyNumber(info.identityPublic, info.id),
                    verified = false,
                    lastSeen = info.joinedAt,
                    online = info.online,
                    unreadCount = 0,
                )
            }
        )
        _peers.value = store.loadPeers()
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    fun openChat(peerId: String) {
        val peer = _peers.value.firstOrNull { it.id == peerId } ?: return
        _screen.value = Screen.Chat(peerId, peer.displayName)
        viewModelScope.launch { _messages.value = store.loadMessages(peerId) }
    }

    fun navigate(screen: Screen) {
        _screen.value = screen
    }

    /**
     * Send text.
     *
     * The message is written to the local database *before* the socket write,
     * so a message survives an app kill mid-send and appears with a retry
     * affordance rather than vanishing.
     */
    fun sendMessage(peerId: String, body: String) {
        val text = body.trim()
        if (text.isEmpty()) return

        val id = UUID.randomUUID().toString()
        val message = Message(
            id = id,
            conversationId = peerId,
            senderId = Message.LOCAL_SENTINEL,
            senderName = displayName,
            body = text,
            sentAt = System.currentTimeMillis(),
            state = DeliveryState.PENDING,
            kind = MessageKind.TEXT,
            deletedForEveryone = false,
        )

        viewModelScope.launch {
            store.insertMessage(message)
            _messages.value = store.loadMessages(peerId)

            val peer = _peers.value.firstOrNull { it.id == peerId }
            val sealed = sessions.encrypt(peerId, peer?.let { prekeyFor(peerId) }, text.toByteArray())
            if (sealed == null) {
                store.updateState(id, DeliveryState.FAILED)
                emitToast("Can't reach ${peer?.displayName ?: "peer"} yet — message held")
            } else if (relay.sendMessage(peerId, sealed)) {
                store.updateState(id, DeliveryState.SENT)
            } else {
                store.updateState(id, DeliveryState.FAILED)
                emitToast("Not connected — message held")
            }
            _messages.value = store.loadMessages(peerId)
        }
    }

    /**
     * The peer's currently published prekey bundle.
     *
     * Read from the live roster rather than the local peer table, because the
     * roster is what the relay is handing out at this instant and a bundle
     * cached at join time may since have been republished.
     *
     * This used to be written as
     *
     *     store.loadPeers().firstOrNull { it.id == peerId }?.let { null } ?: relay…
     *
     * which looks like "try the database, then fall back to the roster". It is
     * not: `?.let { null }` evaluates to null whether or not the peer is found,
     * so the elvis always fell through and the first operand never contributed
     * anything. It read like a fallback chain and silently was not one.
     */
    private suspend fun prekeyFor(peerId: String): String? =
        relay.roster.value.firstOrNull { it.id == peerId }?.prekeyJson

    fun retryMessage(peerId: String, messageId: String) {
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (message.deletedForEveryone) return
        sendMessage(peerId, message.body)
    }

    /**
     * Delete for everyone. HUSH has no edit path anywhere — the feature is
     * absent by design, not merely hidden.
     */
    fun deleteForEveryone(peerId: String, messageId: String) {
        viewModelScope.launch {
            store.markDeletedForEveryone(messageId)
            relay.sendDelete(peerId, messageId)
            _messages.value = store.loadMessages(peerId)
        }
    }

    private suspend fun handleInboundMessage(frame: JSONObject) {
        val from = frame.optString("from")
        if (from.isEmpty() || from == sessions.localPeerId()) return

        val fromName = frame.optString("fromName")
        val kind = frame.optString("kind")
        val payload = runCatching {
            java.util.Base64.getDecoder().decode(frame.optString("envelope"))
        }.getOrNull() ?: return

        val result = sessions.decrypt(from, prekeyFor(from), payload, kind)

        when (result) {
            is dev.hush.chat.data.InboundResult.Text -> {
                store.insertMessage(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = from,
                        senderId = from,
                        senderName = fromName,
                        body = result.body,
                        sentAt = System.currentTimeMillis(),
                        state = DeliveryState.SENT,
                        kind = MessageKind.TEXT,
                        deletedForEveryone = false,
                    )
                )
                if (_screen.value is Screen.Chat && (_screen.value as Screen.Chat).peerId == from) {
                    _messages.value = store.loadMessages(from)
                }
                _peers.value = store.loadPeers()
            }
            is dev.hush.chat.data.InboundResult.Signaling -> {
                SignalingRoute.deliver(from, result.payload)
            }
            is dev.hush.chat.data.InboundResult.Failed -> {
                // A ratchet failure means the session is desynchronised. Drop
                // it so the next message re-establishes from scratch rather
                // than failing forever.
                sessions.resetSession(from)
                emitToast("Could not decrypt a message from $fromName")
            }
            dev.hush.chat.data.InboundResult.Ignored -> Unit
        }
    }

    private suspend fun handleInboundDelete(frame: JSONObject) {
        val msgId = frame.optString("msgId")
        if (msgId.isEmpty()) return
        store.markDeletedForEveryone(msgId)
        val conv = frame.optString("to").ifEmpty { frame.optString("from") }
        if (conv.isNotEmpty()) _messages.value = store.loadMessages(conv)
    }

    private fun handleInboundTyping(frame: JSONObject) {
        val from = frame.optString("from")
        if (from.isEmpty()) return

        if (frame.optBoolean("state")) {
            _typingIn.value = _typingIn.value + from
            typingResetJob?.cancel()
            // Clear after a beat; if the peer is still typing they will keep
            // refreshing it, and if they stopped the indicator should vanish.
            typingResetJob = viewModelScope.launch {
                delay(TYPING_TIMEOUT_MS)
                _typingIn.value = _typingIn.value - from
            }
        } else {
            _typingIn.value = _typingIn.value - from
        }
    }

    /** Called on every keystroke; throttled so we are not one frame per character. */
    fun onTyping(peerId: String) {
        val now = System.currentTimeMillis()
        if (now - typingSentAt < TYPING_THROTTLE_MS) return
        typingSentAt = now
        relay.sendTyping(peerId, true)
    }

    fun stopTyping(peerId: String) {
        if (typingSentAt == 0L) return
        typingSentAt = 0L
        relay.sendTyping(peerId, false)
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    fun search(query: String) {
        viewModelScope.launch {
            _searchHits.value = store.search(query)
        }
    }

    fun clearSearch() {
        _searchHits.value = emptyList()
    }

    fun jumpToHit(hit: SearchHit) {
        openChat(hit.conversationId)
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    fun setVerified(peerId: String, verified: Boolean) {
        viewModelScope.launch {
            store.markVerified(peerId, verified)
            _peers.value = store.loadPeers()
        }
    }

    fun safetyNumberFor(peerId: String): String {
        val peer = _peers.value.firstOrNull { it.id == peerId } ?: return ""
        return TlsPinner.format(sessions.safetyNumber(peer.identityPublic, peerId).replace(" ", ""))
    }

    // -------------------------------------------------------------------------

    fun emitToast(text: String) {
        _toast.value = Toast(text)
    }

    fun consumeToast() {
        _toast.value = null
    }

    override fun onCleared() {
        super.onCleared()
        relay.disconnect()
    }

    companion object {
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_RELAY_PIN = "relay_pin"
        private const val KEY_JOINED_AT = "joined_at"

        private const val TYPING_TIMEOUT_MS = 4_000L
        private const val TYPING_THROTTLE_MS = 1_500L

        /**
         * The public relay every install connects to by default.
         *
         * This is the whole point of the app being usable by someone who has
         * never heard the word "relay". An earlier draft shipped
         * `wss://hush.local:8443/ws` — a placeholder that has never resolved —
         * because no public relay existed when the app was written. It did by
         * the time the app was finished, and shipping the placeholder anyway
         * meant every new install opened to a red "Unable to resolve host"
         * banner and no obvious way out. The field now lives under Advanced,
         * for people running their own.
         */
        const val DEFAULT_RELAY = "wss://spudpotato.itskeshavm.workers.dev/ws"
    }
}
