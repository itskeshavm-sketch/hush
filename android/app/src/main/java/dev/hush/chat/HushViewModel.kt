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
 * Outcome of a lock-screen PIN entry.
 *
 * [Wiped] is deliberately shaped exactly like [Open] to the caller: the lock
 * screen shows nothing different, because a duress PIN that announces itself
 * protects no one.
 */
sealed interface UnlockResult {
    data object Open : UnlockResult
    data object Wiped : UnlockResult
    data class Refused(val retryInMs: Long) : UnlockResult
}

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

    private val _themeKey =
        MutableStateFlow(prefs.getString(KEY_APP_THEME, dev.hush.chat.ui.theme.HushThemeKeys.DEFAULT) ?: dev.hush.chat.ui.theme.HushThemeKeys.DEFAULT)
    val themeKey: StateFlow<String> = _themeKey.asStateFlow()

    /**
     * Persist the selected appearance theme. Unknown keys are ignored rather
     * than stored: a stale or hand-edited pref must fall back to the default
     * instead of leaving the app in a theme that does not exist.
     */
    fun setTheme(key: String) {
        if (dev.hush.chat.ui.theme.HushThemeCatalog.none { it.key == key }) return
        prefs.edit().putString(KEY_APP_THEME, key).apply()
        _themeKey.value = key
    }

    // -------------------------------------------------------------------------
    // App lock + duress PIN
    // -------------------------------------------------------------------------

    private val _locked = MutableStateFlow(hasAppPin())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _pinSet = MutableStateFlow(hasAppPin())
    val pinSet: StateFlow<Boolean> = _pinSet.asStateFlow()

    private val _duressSet = MutableStateFlow(hasDuressPin())
    val duressSet: StateFlow<Boolean> = _duressSet.asStateFlow()

    private var lastPausedAt = 0L

    fun hasAppPin(): Boolean = prefs.getString(KEY_PIN_HASH, null) != null
    fun hasDuressPin(): Boolean = prefs.getString(KEY_DURESS_HASH, null) != null

    /** Store a new app PIN. Returns false when the format is wrong. */
    fun setAppPin(pin: String): Boolean {
        if (!validPinFormat(pin)) return false
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
        _pinSet.value = true
        return true
    }

    /**
     * Store the duress PIN. Requires an app PIN to already exist, and refuses
     * a duress PIN equal to it — same salt, same hash means same PIN, and a
     * duress PIN you cannot distinguish from the real one is the whole point,
     * but one that IS the real one wipes the owner on a normal unlock.
     */
    fun setDuressPin(pin: String): Boolean {
        val appHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        if (!validPinFormat(pin)) return false
        if (hashPinWithSalt(pin, saltOf(appHash)) == appHash) return false
        prefs.edit().putString(KEY_DURESS_HASH, hashPin(pin)).apply()
        _duressSet.value = true
        return true
    }

    /** Remove both PINs after verifying the current app PIN. */
    fun removePins(currentPin: String): Boolean {
        if (!checkPin(currentPin)) return false
        prefs.edit().remove(KEY_PIN_HASH).remove(KEY_DURESS_HASH).apply()
        _pinSet.value = false
        _duressSet.value = false
        _locked.value = false
        return true
    }

    fun verifyPin(pin: String): Boolean = checkPin(pin)

    fun lockNow() {
        if (hasAppPin()) _locked.value = true
    }

    fun onAppBackgrounded() {
        lastPausedAt = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        if (hasAppPin() && lastPausedAt > 0 &&
            System.currentTimeMillis() - lastPausedAt > LOCK_TIMEOUT_MS
        ) {
            _locked.value = true
        }
        // Reopening must show the truth immediately, not the roster as it was
        // when the app was backgrounded. A locked install reconnects on unlock
        // (fresh welcome roster); an unlocked one just asks.
        if (!_locked.value) relay.refreshRoster()
    }

    /**
     * Attempt an unlock. The duress hash is checked FIRST: a duress PIN must
     * open into a wiped install, and checking the app PIN first would change
     * nothing observable — but order matters for the fail counter, which a
     * duress entry must never increment.
     */
    fun unlock(pin: String): UnlockResult {
        val now = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        if (now < lockoutUntil) {
            return UnlockResult.Refused(lockoutUntil - now)
        }

        prefs.getString(KEY_DURESS_HASH, null)?.let { duressHash ->
            if (hashPinWithSalt(pin, saltOf(duressHash)) == duressHash) {
                wipeAllData()
                return UnlockResult.Wiped
            }
        }

        if (checkPin(pin)) {
            prefs.edit().remove(KEY_PIN_FAILS).remove(KEY_PIN_LOCKOUT_UNTIL).apply()
            _locked.value = false
            if (displayName.isNotEmpty()) startRelay()
            return UnlockResult.Open
        }

        val fails = prefs.getInt(KEY_PIN_FAILS, 0) + 1
        return if (fails >= MAX_PIN_FAILS) {
            val wait = PIN_LOCKOUT_MS
            prefs.edit()
                .putInt(KEY_PIN_FAILS, 0)
                .putLong(KEY_PIN_LOCKOUT_UNTIL, now + wait)
                .apply()
            UnlockResult.Refused(wait)
        } else {
            prefs.edit().putInt(KEY_PIN_FAILS, fails).apply()
            UnlockResult.Refused(0L)
        }
    }

    /**
     * Total erasure: messages, peers, identity, prekeys, pins, theme, relay
     * pin. The in-memory ratchet sessions are wiped too, so key material does
     * not survive in the process either. Afterwards the install is
     * indistinguishable from fresh — onboarding, unlocked, no PIN.
     */
    private fun wipeAllData() {
        viewModelScope.launch {
            runCatching { store.nuke() }
            sessions.clearAll()
            relay.disconnect()
            prefs.edit().clear().apply()
            getApplication<Application>().getSharedPreferences("hush_identity", Application.MODE_PRIVATE)
                .edit().clear().apply()
            getApplication<Application>().getSharedPreferences("hush_prekeys", Application.MODE_PRIVATE)
                .edit().clear().apply()
            // Mint a fresh identity NOW, not on next launch. Without this,
            // completing onboarding after a wipe calls buildHello with a null
            // identity and crashes — which is the "kicked off, reopen, works"
            // pattern: the relaunch ran init's loadOrCreateIdentity first.
            sessions.loadOrCreateIdentity()
            lastOnlineIds = emptySet()
            _peers.value = emptyList()
            _messages.value = emptyList()
            _themeKey.value = dev.hush.chat.ui.theme.HushThemeKeys.DEFAULT
            _pinSet.value = false
            _duressSet.value = false
            _locked.value = false
            _screen.value = Screen.Onboarding
        }
    }

    private fun validPinFormat(pin: String): Boolean =
        pin.length in MIN_PIN_LEN..MAX_PIN_LEN && pin.all { it.isDigit() }

    private fun checkPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return hashPinWithSalt(pin, saltOf(stored)) == stored
    }

    private fun hashPin(pin: String): String {
        val salt = ByteArray(PIN_SALT_LEN)
        java.security.SecureRandom().nextBytes(salt)
        return combine(salt, sha256(salt + pin.toByteArray()))
    }

    private fun hashPinWithSalt(pin: String, salt: ByteArray): String =
        combine(salt, sha256(salt + pin.toByteArray()))

    private fun saltOf(stored: String): ByteArray = try {
        val saltB64 = stored.substringBefore(':')
        java.util.Base64.getDecoder().decode(saltB64)
    } catch (e: Exception) {
        ByteArray(0)
    }

    private fun combine(salt: ByteArray, hash: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(salt) + ":" +
            java.util.Base64.getEncoder().encodeToString(hash)

    private fun sha256(input: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(input)

    private var joinLoop: Job? = null
    private var typingResetJob: Job? = null
    private var typingSentAt = 0L
    private var expiryLoop: Job? = null
    private var rosterPoll: Job? = null
    private var lastOnlineIds: Set<String> = emptySet()

    init {
        sessions.loadOrCreateIdentity()
        if (displayName.isNotEmpty()) {
            _screen.value = Screen.Roster
            // A locked install does not connect until the PIN opens it: no
            // roster, no presence, no inbound frames while gated.
            if (!hasAppPin()) startRelay()
        }
        // Expiry sweep runs for the life of the process, not just while a chat
        // is open: a message must disappear even if the user never opens that
        // conversation again. It also re-sends recent receipts for the open
        // chat, so a single dropped receipt frame cannot leave the two sides
        // diverged forever.
        if (expiryLoop == null) {
            expiryLoop = viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(EXPIRY_SWEEP_MS)
                    runCatching {
                        expireDue()
                        (_screen.value as? Screen.Chat)?.let {
                            flushSeenReceipts(it.peerId, resendSeenSinceMs = System.currentTimeMillis() - RECEIPT_RESEND_WINDOW_MS)
                        }
                    }
                }
            }
        }
        // Presence convergence poll. Broadcasts are best-effort on a flaky
        // socket; this bounds staleness to ROSTER_POLL_MS no matter how many
        // were missed. One tiny unicast exchange per interval.
        if (rosterPoll == null) {
            rosterPoll = viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(ROSTER_POLL_MS)
                    runCatching {
                        if (relay.state.value is RelayState.Connected) relay.refreshRoster()
                    }
                }
            }
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

        // Both collectors are failure-contained: an exception in one frame
        // must never kill the loop. A dead collector looks exactly like the
        // bug where presence freezes until the app is reopened — the socket is
        // fine, broadcasts arrive, but nobody is listening anymore.
        viewModelScope.launch {
            relay.roster.collect { roster ->
                runCatching { syncRoster(roster) }
                    .onFailure { android.util.Log.w("HushVM", "roster sync failed", it) }
            }
        }

        viewModelScope.launch {
            relay.frames.collect { frame ->
                runCatching { handleFrame(frame) }
                    .onFailure { android.util.Log.w("HushVM", "frame handling failed", it) }
            }
        }
    }

    /** Dispatch one inbound frame. Never throws — see [observeFrames]. */
    private suspend fun handleFrame(frame: JSONObject) {
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
            "seen" -> handleInboundSeen(frame)
            "kept" -> handleInboundKept(frame)
            "reset" -> handleInboundReset(frame)
            "typing" -> handleInboundTyping(frame)
            "error" -> handleRelayError(frame)
        }
    }

    /**
     * A peer asks us to drop our ratchet session for them — their side kept
     * failing to decrypt. Comply immediately (our side resets too, so both
     * halves are fresh), re-queue anything recently sent (it went out on the
     * stale session: relay-accepted but unreadable, so the peer never saw
     * it), and flush under the fresh session. Same stable ids throughout, so
     * the re-delivery replaces nothing and duplicates nothing.
     */
    private suspend fun handleInboundReset(frame: JSONObject) {
        val by = frame.optString("by").ifEmpty { frame.optString("from") }
        if (by.isEmpty()) return
        sessions.resetSession(by)
        store.demoteRecent(by, System.currentTimeMillis() - RESET_RESEND_WINDOW_MS)
        flushPendingMessages(by)
        if (!shouldToast("reset-in:$by")) return
        val name = _peers.value.firstOrNull { it.id == by }?.displayName
            ?.ifEmpty { null }
            ?: frame.optString("byName").ifEmpty { "Peer" }
        emitToast("Re-established secure session with $name")
    }

    /**
     * Relay errors, translated for humans. The relay addresses peers by
     * routing id (`p_…`), which is meaningless on screen — so ids are mapped
     * to display names before anything reaches a toast. An unmappable id
     * falls back to "that peer" rather than leaking wire internals.
     *
     * `unknown_peer` also repairs state: sends are marked SENT optimistically
     * on socket-write success, but the relay drops them when the target has
     * no live socket. Without a demote here those rows stay SENT forever —
     * excluded from the offline queue — and never flush when the peer
     * returns. Demoting recent SENT back to PENDING puts them back in the
     * queue; re-delivery is idempotent by stable id.
     */
    private suspend fun handleRelayError(frame: JSONObject) {
        val code = frame.optString("code")
        val raw = frame.optString("message").ifEmpty { "relay error" }
        if (code == "unknown_peer") {
            val id = raw.substringBefore(" is offline").takeIf { raw.endsWith(" is offline") }
            val name = id?.let { peerNameFor(it) } ?: "That peer"
            if (id != null) {
                runCatching {
                    store.demoteRecent(id, System.currentTimeMillis() - OFFLINE_DEMOTE_WINDOW_MS)
                }
                if ((_screen.value as? Screen.Chat)?.peerId == id) {
                    runCatching { _messages.value = store.loadMessages(id) }
                }
            }
            emitToast("$name is offline — message queued, sends when they're back")
            return
        }
        emitToast(raw)
    }

    /** Display name for a peer id, or null when unknown. */
    private fun peerNameFor(peerId: String): String? =
        _peers.value.firstOrNull { it.id == peerId }?.displayName?.ifEmpty { null }

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
        // Peers the relay no longer reports are offline, not gone — except rows
        // with no conversation history at all, which are roster cruft (a device
        // that happened to be online while a test probe connected, for
        // example) and are deleted outright. Without this, names like the
        // throwaway test bots linger on the device that saw them and never on
        // a device installed later, which reads as "I see people my phone
        // doesn't" and is indistinguishable from a sync bug.
        store.reconcilePeers(others.map { it.id })
        _peers.value = store.loadPeers()
        // Receipts are fire-and-forget and the relay drops them for offline
        // peers. The two moments a receipt has a live route are chat-open
        // (see openChat) and peer-offline-to-online while the chat is open.
        // Without this, a receipt sent while the peer was gone is lost and the
        // sender's after-seen timer never starts.
        val nowOnline = others.filter { it.online }.map { it.id }.toSet()
        val newlyOnline = nowOnline - lastOnlineIds
        val openPeer = (_screen.value as? Screen.Chat)?.peerId
        if (openPeer != null && openPeer in nowOnline && openPeer !in lastOnlineIds) {
            // Re-send includes already-seen timer messages: if our first
            // receipt was the one dropped frame, this is what arms the
            // sender's copy. First-wins on their side keeps duplicates from
            // extending the deadline.
            flushSeenReceipts(openPeer, resendSeenSinceMs = 0L)
        }
        // Offline queue: the relay stores nothing for absent peers, so anything
        // we wrote while they were gone goes out now that they are back.
        // Checked for every online peer on every roster event (gated by a
        // cheap exists-query), not just newly-online ones: a flush can fail
        // transiently (socket mid-handshake, bundle not yet published), and
        // with only newly-online triggering, one failed flush stuck the
        // outbox until the next reconnect.
        nowOnline.forEach { id ->
            if (store.hasPending(id)) flushPendingMessages(id)
        }
        lastOnlineIds = nowOnline
    }

    // -------------------------------------------------------------------------
    // Messaging
    // -------------------------------------------------------------------------

    fun openChat(peerId: String) {
        val peer = _peers.value.firstOrNull { it.id == peerId } ?: return
        _screen.value = Screen.Chat(peerId, peer.displayName)
        viewModelScope.launch {
            _messages.value = store.loadMessages(peerId)
            // Opening a chat IS seeing it. Receipts go out for everything
            // unacked, plus a re-send of already-seen timer messages — after
            // an app restart the first receipt may be the one that never left,
            // and only a duplicate heals the sender's deadline.
            flushSeenReceipts(peerId, resendSeenSinceMs = 0L)
        }
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
        val now = System.currentTimeMillis()
        val (sentTtl, seenTtl) = policyFor(peerId)
        val message = Message(
            id = id,
            conversationId = peerId,
            senderId = Message.LOCAL_SENTINEL,
            senderName = displayName,
            body = text,
            sentAt = now,
            state = DeliveryState.PENDING,
            kind = MessageKind.TEXT,
            deletedForEveryone = false,
            // Sent-based timers start now on both sides. Seen-based timers
            // start at receipt time — but bounded: if no receipt ever arrives
            // (dropped while the peer was offline), the backstop deletes our
            // copy anyway instead of keeping a "disappearing" message forever.
            // No policy at all means never: 0, not now-plus-anything.
            expiresAt = when {
                sentTtl != null -> now + sentTtl
                seenTtl != null -> now + maxOf(seenTtl, SEEN_BACKSTOP_MS)
                else -> 0L
            },
            expirySeenMs = seenTtl ?: 0L,
        )

        viewModelScope.launch {
            store.insertMessage(message)
            _messages.value = store.loadMessages(peerId)

            val peer = _peers.value.firstOrNull { it.id == peerId }
            val bundleJson = peer?.let { prekeyFor(peerId) }
            // The id travels inside the ciphertext so the recipient stores the
            // same row id. Without this, delete-for-everyone addresses an id
            // the other side has never seen.
            val sealed = sessions.encrypt(peerId, bundleJson, sessions.sealText(id, text, sentTtl, seenTtl))
            if (sealed == null) {
                store.updateState(id, DeliveryState.FAILED)
                // Null bundle means they never published (still connecting).
                // Non-null but unencryptable means the bundle fails
                // verification — a browser preview or test client that can
                // never read an app message, not someone who is merely offline.
                if (bundleJson != null) {
                    emitToast(
                        "${peer?.displayName ?: "That peer"} is using the browser preview " +
                            "or a test client, which can't read app messages",
                    )
                } else {
                    emitToast("Can't reach ${peer?.displayName ?: "peer"} yet — message queued, sends when they're back")
                }
            } else if (relay.sendMessage(peerId, sealed)) {
                // Socket-write success is optimistic: the relay drops sends
                // for offline peers and reports unknown_peer async, and the
                // demote in handleRelayError puts those rows back in the
                // queue. The local roster is NOT consulted here on purpose —
                // it polls at 30s and goes stale the moment a peer returns,
                // so gating on it turned every just-returned peer into a false
                // "offline" with a retry badge (the bug in the report).
                store.updateState(id, DeliveryState.SENT)
            } else {
                store.updateState(id, DeliveryState.FAILED)
                emitToast("Not connected — message held")
            }
            _messages.value = store.loadMessages(peerId)
        }
    }

    /**
     * Flush one queued message: seal the same stable id again and send it.
     *
     * The id is reused, never minted fresh — the recipient stores rows by this
     * id with CONFLICT_REPLACE, so a re-send is idempotent, and a later
     * delete-for-everyone addresses the id both sides hold. Minting a new id
     * here (as retry used to) orphans the recipient's copy from future
     * deletes and receipts.
     *
     * @return true when the row changed state and the transcript needs reload.
     */
    private suspend fun sendQueued(peerId: String, item: dev.hush.chat.data.OutboxItem): Boolean {
        if (item.kind == MessageKind.IMAGE && item.attachment != null) {
            val raw = runCatching {
                java.util.Base64.getDecoder().decode(item.attachment)
            }.getOrNull() ?: return false
            return sendImageBytes(peerId, item.id, item.body, raw)
        }
        return sendQueuedText(peerId, item.id, item.body)
    }

    private suspend fun sendQueued(peerId: String, id: String, body: String): Boolean =
        sendQueuedText(peerId, id, body)

    private suspend fun sendQueuedText(peerId: String, id: String, body: String): Boolean {
        val (sentTtl, seenTtl) = policyFor(peerId)
        val bundleJson = prekeyFor(peerId) ?: return false
        val sealed = sessions.encrypt(peerId, bundleJson, sessions.sealText(id, body, sentTtl, seenTtl))
        if (sealed == null) {
            // Bundle present but unusable: a browser preview or test client
            // that can never read app messages. Park it as failed so the
            // flush loop stops retrying every roster event, and say so once.
            store.updateState(id, DeliveryState.FAILED)
            if (shouldToast("flush:$peerId")) {
                val peer = _peers.value.firstOrNull { it.id == peerId }
                emitToast(
                    "${peer?.displayName ?: "That peer"} is using the browser preview " +
                        "or a test client, which can't read app messages",
                )
            }
            return true
        }
        if (!relay.sendMessage(peerId, sealed)) return false
        store.updateState(id, DeliveryState.SENT)
        return true
    }

    /**
     * Send everything queued while this peer was unreachable.
     *
     * Called when the relay reports the peer back online. Messages whose own
     * timer expired while waiting are skipped by the query (tombstoned rows
     * are excluded) — a message that died waiting is never delivered late.
     */
    private suspend fun flushPendingMessages(peerId: String) {
        val pending = store.pendingOutgoing(peerId)
        if (pending.isEmpty()) return
        var changed = false
        pending.forEach { changed = sendQueued(peerId, it) || changed }
        if (changed && (_screen.value as? Screen.Chat)?.peerId == peerId) {
            _messages.value = store.loadMessages(peerId)
        }
    }

    /**
     * Send an image.
     *
     * [jpegBytes] should already be a compressed JPEG (see [compressForSend]).
     * The bytes travel as numbered E2E chunks under one stable id, exactly
     * like text travels as one payload — same timers, same receipts, same
     * delete-for-everyone. The row stores the base64 JPEG in `attachment`
     * (never in `body`, so search indexes nothing but the empty caption).
     */
    fun sendImage(peerId: String, jpegBytes: ByteArray) {
        if (jpegBytes.isEmpty()) return
        if (jpegBytes.size > MAX_IMAGE_BYTES) {
            emitToast("That image is too big even compressed — try a smaller one")
            return
        }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val (sentTtl, seenTtl) = policyFor(peerId)
        val b64 = java.util.Base64.getEncoder().encodeToString(jpegBytes)
        val message = Message(
            id = id,
            conversationId = peerId,
            senderId = Message.LOCAL_SENTINEL,
            senderName = displayName,
            body = "",
            sentAt = now,
            state = DeliveryState.PENDING,
            kind = MessageKind.IMAGE,
            deletedForEveryone = false,
            attachmentLabel = b64,
            expiresAt = when {
                sentTtl != null -> now + sentTtl
                seenTtl != null -> now + maxOf(seenTtl, SEEN_BACKSTOP_MS)
                else -> 0L
            },
            expirySeenMs = seenTtl ?: 0L,
        )
        viewModelScope.launch {
            store.insertMessage(message)
            _messages.value = store.loadMessages(peerId)
            if (sendImageBytes(peerId, id, "", jpegBytes)) {
                // Same optimistic rule as text (see sendMessage): no roster
                // gate, relay error demotes.
                store.updateState(id, DeliveryState.SENT)
            } else {
                store.updateState(id, DeliveryState.FAILED)
                emitToast("Can't reach them yet — image queued, sends when they're back")
            }
            _messages.value = store.loadMessages(peerId)
        }
    }

    /**
     * Downscale + JPEG-compress raw image bytes for sending.
     *
     * Returns null when the image cannot be decoded or cannot be squeezed
     * under [MAX_IMAGE_BYTES]. Re-encoding through Bitmap drops most file
     * metadata (EXIF/GPS) in practice — but that is an implementation detail,
     * not a promise, which is exactly why the send flow still warns that
     * metadata may survive and stay traceable.
     */
    fun compressForSend(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        if (raw.size <= MAX_IMAGE_BYTES) {
            // Still re-encode small images: it normalises exotic formats
            // (HEIC/PNG) to JPEG the receiver can always decode, and sheds
            // metadata containers those formats carry.
            decodeBounded(raw, 1600)?.let { bmp ->
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                bmp.recycle()
                return out.toByteArray().takeIf { it.isNotEmpty() }
            }
            return null
        }
        for ((maxDim, quality) in listOf(1600 to 78, 1280 to 70, 1024 to 62)) {
            val bmp = decodeBounded(raw, maxDim) ?: continue
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            val bytes = out.toByteArray()
            if (bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) return bytes
        }
        return null
    }

    private fun decodeBounded(raw: ByteArray, maxDim: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample) > maxDim || (bounds.outHeight / sample) > maxDim) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /**
     * Seal and send every chunk of one image. All-or-nothing per attempt: a
     * false return leaves the row queued and the next flush re-sends every
     * chunk — the receiver overwrites duplicate indexes, so re-sends are
     * idempotent and never corrupt a partial assembly.
     */
    private suspend fun sendImageBytes(peerId: String, id: String, caption: String, raw: ByteArray): Boolean {
        val (sentTtl, seenTtl) = policyFor(peerId)
        val bundleJson = prekeyFor(peerId) ?: return false
        val total = (raw.size + dev.hush.chat.data.SessionManager.MAX_CHUNK_RAW - 1) /
            dev.hush.chat.data.SessionManager.MAX_CHUNK_RAW
        if (total < 1 || total > dev.hush.chat.data.SessionManager.MAX_IMAGE_CHUNKS) return false
        for (index in 0 until total) {
            val start = index * dev.hush.chat.data.SessionManager.MAX_CHUNK_RAW
            val end = minOf(start + dev.hush.chat.data.SessionManager.MAX_CHUNK_RAW, raw.size)
            val dataB64 = java.util.Base64.getEncoder()
                .encodeToString(raw.copyOfRange(start, end))
            val sealed = sessions.encrypt(
                peerId, bundleJson,
                sessions.sealImageChunk(id, caption, index, total, dataB64, sentTtl, seenTtl),
            ) ?: return false
            if (!relay.sendMessage(peerId, sealed)) return false
        }
        return true
    }

    /** One image being reassembled from chunks, keyed by sender message id. */
    private data class ImageAssembly(
        val caption: String,
        val total: Int,
        val expSentMs: Long?,
        val expSeenMs: Long?,
        val parts: Array<ByteArray?>,
        var received: Int = 0,
    )

    private val imageParts = java.util.concurrent.ConcurrentHashMap<String, ImageAssembly>()

    private suspend fun handleImageChunk(
        from: String,
        fromName: String,
        chunk: dev.hush.chat.data.InboundResult.ImageChunk,
    ) {
        // Cap concurrent assemblies: each holds up to ~2.8 MB, and an
        // attacker announcing many ids must not be able to grow the heap.
        if (!imageParts.containsKey(chunk.msgId) && imageParts.size >= MAX_IMAGE_ASSEMBLIES) return
        val asm = imageParts.computeIfAbsent(chunk.msgId) {
            ImageAssembly(chunk.caption, chunk.total, chunk.expSentMs, chunk.expSeenMs, arrayOfNulls(chunk.total))
        }
        // A mismatched re-send (different total/caption, same id) is garbage,
        // not an update — drop it rather than corrupting the assembly.
        if (asm.total != chunk.total) return
        if (asm.parts[chunk.index] == null) {
            asm.parts[chunk.index] = chunk.data
            asm.received++
        }
        if (asm.received < asm.total) return
        imageParts.remove(chunk.msgId)

        val out = java.io.ByteArrayOutputStream()
        asm.parts.forEach { part -> if (part != null) out.write(part) }
        val jpeg = out.toByteArray()
        if (jpeg.isEmpty()) return

        val now = System.currentTimeMillis()
        store.insertMessage(
            Message(
                id = chunk.msgId,
                conversationId = from,
                senderId = from,
                senderName = fromName,
                body = asm.caption,
                sentAt = now,
                state = DeliveryState.SENT,
                kind = MessageKind.IMAGE,
                deletedForEveryone = false,
                attachmentLabel = java.util.Base64.getEncoder().encodeToString(jpeg),
                expiresAt = when {
                    asm.expSentMs != null -> now + asm.expSentMs
                    asm.expSeenMs != null -> now + maxOf(asm.expSeenMs, SEEN_BACKSTOP_MS)
                    else -> 0L
                },
                expirySeenMs = asm.expSeenMs ?: 0L,
            )
        )
        if (_screen.value is Screen.Chat && (_screen.value as Screen.Chat).peerId == from) {
            flushSeenReceipts(from)
        }
        _peers.value = store.loadPeers()
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
        // Re-send the SAME id, not a fresh message. A retry that mints a new
        // id leaves the recipient holding an id we no longer reference, so a
        // later delete-for-everyone or receipt addresses nothing on their side.
        viewModelScope.launch {
            store.updateState(messageId, DeliveryState.PENDING)
            _messages.value = store.loadMessages(peerId)
            // Nudge presence before re-sealing: the bundle lives in the roster
            // frame, so a stale frame means no bundle and a guaranteed retry
            // failure. Fire-and-forget — the current attempt proceeds with
            // whatever we have; the next roster event heals the rest.
            runCatching { relay.refreshRoster() }
            val item = dev.hush.chat.data.OutboxItem(
                id = messageId,
                body = message.body,
                kind = message.kind,
                attachment = message.attachmentLabel,
            )
            if (sendQueued(peerId, item)) {
                _messages.value = store.loadMessages(peerId)
            } else {
                store.updateState(messageId, DeliveryState.FAILED)
                _messages.value = store.loadMessages(peerId)
                emitToast("Still can't reach them — kept in the queue")
            }
        }
    }

    fun sendImageRaw(peerId: String, raw: ByteArray) {
        viewModelScope.launch {
            val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                compressForSend(raw)
            }
            if (jpeg == null) {
                emitToast("Couldn't read that image — try another")
                return@launch
            }
            sendImage(peerId, jpeg)
        }
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

    // -------------------------------------------------------------------------
    // Disappearing messages
    // -------------------------------------------------------------------------

    /**
     * Per-conversation timer, serialised as "off", "seen:<ms>" or "sent:<ms>"
     * in prefs. Absent means the default: 2 minutes after seen. The default
     * lives in exactly one place ([DEFAULT_DISAPPEARING]) so the composer,
     * the labels, and the payload can never disagree about it.
     */
    fun disappearingFor(peerId: String): String =
        prefs.getString(KEY_DISAPPEARING_PREFIX + peerId, null)
            ?.takeIf { validDisappearing(it) }
            ?: DEFAULT_DISAPPEARING

    fun setDisappearing(peerId: String, value: String) {
        if (!validDisappearing(value)) return
        prefs.edit().putString(KEY_DISAPPEARING_PREFIX + peerId, value).apply()
    }

    fun isDisappearingDefault(peerId: String): Boolean =
        prefs.getString(KEY_DISAPPEARING_PREFIX + peerId, null) == null

    /** Effective (sent-timer, seen-timer) pair for the next message. */
    private fun policyFor(peerId: String): Pair<Long?, Long?> {
        val v = disappearingFor(peerId)
        if (v == "off") return null to null
        val ms = v.substringAfter(':', "").toLongOrNull()
            ?.takeIf { it in 1..dev.hush.chat.data.SessionManager.MAX_TIMER_MS }
            ?: return defaultPolicy()
        return if (v.startsWith("sent:")) ms to null else null to ms
    }

    private fun defaultPolicy(): Pair<Long?, Long?> = null to DEFAULT_SEEN_MS

    private fun validDisappearing(v: String): Boolean {
        if (v == "off") return true
        val parts = v.split(':')
        if (parts.size != 2) return false
        if (parts[0] != "seen" && parts[0] != "sent") return false
        val parsed = parts[1].toLongOrNull() ?: return false
        return parsed in 1..dev.hush.chat.data.SessionManager.MAX_TIMER_MS
    }

    /** Short human label: "2 min", "24 hours", "7 days". */
    fun shortDur(ms: Long): String = when {
        ms % 86_400_000L == 0L -> "${ms / 86_400_000L} day" + if (ms == 86_400_000L) "" else "s"
        ms % 3_600_000L == 0L -> "${ms / 3_600_000L} hour" + if (ms == 3_600_000L) "" else "s"
        ms % 60_000L == 0L -> "${ms / 60_000L} min"
        ms % 1_000L == 0L -> "${ms / 1_000L}s"
        else -> "${ms}ms"
    }

    /** Full label for the timer picker and chat subtitle. */
    fun disappearingLabel(value: String): String {
        if (value == "off") return "Off"
        val ms = value.substringAfter(':', "").toLongOrNull() ?: return "Off"
        return if (value.startsWith("sent:")) "${shortDur(ms)} after sent"
        else "${shortDur(ms)} after seen"
    }

    /**
     * Keep a received message past its timer. Only beats the timer — an
     * explicit delete-for-everyone from the sender still tombstones a kept
     * copy, and the sender is told immediately (a silent keep would be a
     * betrayal shaped like a feature).
     */
    fun keepMessage(peerId: String, messageId: String) {
        viewModelScope.launch {
            store.setKept(messageId, true)
            relay.sendKept(peerId, messageId)
            _messages.value = store.loadMessages(peerId)
        }
    }

    /**
     * Send receipts for everything unacked in this conversation, then arm our
     * own deadlines. Called on chat open and on peer-online transitions while
     * the chat is open — the two moments a receipt has a live route.
     *
     * @param resendSeenSinceMs when non-null, also re-send receipts for
     *   already-seen timer messages seen since that time. A receipt is one
     *   fire-and-forget frame: if that single frame was the one dropped, the
     *   receiver deletes on schedule while the sender keeps the message for
     *   the full backstop. Duplicates are harmless (the sender anchors at the
     *   first receipt), so re-sending heals the divergence instead of risking
     *   it. Pass 0 to re-send all unexpired ones (chat open, peer returned),
     *   or a recent cutoff for the periodic sweep.
     */
    private suspend fun flushSeenReceipts(peerId: String, resendSeenSinceMs: Long? = null) {
        val fresh = store.unseenInboundIds(peerId)
        val resend = resendSeenSinceMs?.let { store.seenTimerIds(peerId, it) } ?: emptyList()
        val ids = (fresh + resend).distinct()
        if (ids.isEmpty()) return
        ids.forEach { relay.sendSeen(peerId, it) }
        if (fresh.isNotEmpty()) {
            store.markLocalSeen(peerId, System.currentTimeMillis())
        }
        if ((_screen.value as? Screen.Chat)?.peerId == peerId) {
            _messages.value = store.loadMessages(peerId)
        }
    }

    /** Tombstone whatever expired; reload only if something actually went. */
    private suspend fun expireDue() {
        if (store.expireDue(System.currentTimeMillis()) > 0) {
            (_screen.value as? Screen.Chat)?.let { _messages.value = store.loadMessages(it.peerId) }
            _peers.value = store.loadPeers()
        }
    }

    private suspend fun handleInboundSeen(frame: JSONObject) {
        val msgId = frame.optString("msgId")
        if (msgId.isEmpty()) return
        store.markPeerSeen(msgId, System.currentTimeMillis())
        expireDue()
        (_screen.value as? Screen.Chat)?.let { _messages.value = store.loadMessages(it.peerId) }
    }

    private suspend fun handleInboundKept(frame: JSONObject) {
        val by = frame.optString("by").ifEmpty { frame.optString("from") }
        val byName = frame.optString("byName").ifEmpty { "They" }
        val msgId = frame.optString("msgId")
        if (by.isEmpty() || msgId.isEmpty()) return
        // The sender's copy keeps its own timer — only the keeper's copy is
        // exempt. What the sender gets is the truth about it, as a system row
        // in the same transcript, not a toast that scrolls away.
        store.insertMessage(
            Message(
                id = UUID.randomUUID().toString(),
                conversationId = by,
                senderId = by,
                senderName = byName,
                body = "$byName kept a message",
                sentAt = System.currentTimeMillis(),
                state = DeliveryState.SENT,
                kind = MessageKind.SYSTEM,
                deletedForEveryone = false,
            )
        )
        if ((_screen.value as? Screen.Chat)?.peerId == by) {
            _messages.value = store.loadMessages(by)
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
                val now = System.currentTimeMillis()
                store.insertMessage(
                    Message(
                        // Sender-stable id: the same value the sender holds, so
                        // a later delete frame addressed by that id matches.
                        // CONFLICT_REPLACE in the store makes a redelivery
                        // idempotent rather than a duplicate.
                        id = result.msgId,
                        conversationId = from,
                        senderId = from,
                        senderName = fromName,
                        body = result.body,
                        sentAt = now,
                        state = DeliveryState.SENT,
                        kind = MessageKind.TEXT,
                        deletedForEveryone = false,
                        // Sent-based deadline starts at receipt (local clocks
                        // differ; receive-relative is skew-tolerant). Seen
                        // starts at display; bounded by the same backstop as
                        // the sender so an unopened message still eventually
                        // goes instead of keeping a "disappearing" promise
                        // forever.
                        expiresAt = when {
                            result.expSentMs != null -> now + result.expSentMs
                            result.expSeenMs != null -> now + maxOf(result.expSeenMs, SEEN_BACKSTOP_MS)
                            else -> 0L
                        },
                        expirySeenMs = result.expSeenMs ?: 0L,
                    )
                )
                if (_screen.value is Screen.Chat && (_screen.value as Screen.Chat).peerId == from) {
                    flushSeenReceipts(from)
                }
                _peers.value = store.loadPeers()
            }
            is dev.hush.chat.data.InboundResult.ImageChunk -> {
                handleImageChunk(from, fromName, result)
            }
            is dev.hush.chat.data.InboundResult.Signaling -> {
                SignalingRoute.deliver(from, result.payload)
            }
            is dev.hush.chat.data.InboundResult.Failed -> {
                // A ratchet failure means the session is desynchronised. Drop
                // it so the next message re-establishes from scratch rather
                // than failing forever.
                //
                // Both sides must run the build with stable prekey bundles for
                // this to heal: if the sender is still on the build that wipes
                // its private halves, no number of resets on this side will
                // help, and the toast says so instead of implying a retry will
                // fix it.
                sessions.resetSession(from)
                // Tell the SENDER to drop their side too. Without this, they
                // keep encrypting on the stale session while we fail every
                // frame — the "could not decrypt" loop that never ends.
                // Throttled: one request per peer per minute, and the next
                // message from either side heals via fresh X3DH regardless.
                if (shouldToast("reset:$from")) relay.sendReset(from)
                if (shouldToast("fail:$from")) {
                    emitToast(
                        "Could not decrypt a message from $fromName — " +
                            "make sure both of you updated to the latest install",
                    )
                }
            }
            is dev.hush.chat.data.InboundResult.Incompatible -> {
                // The sender is not speaking the app protocol: the browser
                // preview (XOR envelopes, random bundle signatures) or a relay
                // test probe. Resetting cannot help — there is no session to
                // heal — so say what it is, throttled, and move on without
                // storing a poisoned session.
                sessions.resetSession(from)
                if (!shouldToast("incompat:$from")) return
                val name = fromName.ifEmpty { "that peer" }
                emitToast(
                    "$name is using the browser preview or a test client, " +
                        "which can't exchange encrypted messages with the app",
                )
            }
            dev.hush.chat.data.InboundResult.Ignored -> Unit
        }
    }

    private suspend fun handleInboundDelete(frame: JSONObject) {
        val msgId = frame.optString("msgId")
        if (msgId.isEmpty()) return
        store.markDeletedForEveryone(msgId)
        // The relay forwards deletes as {by: sender, to: recipient, msgId}.
        // The conversation to refresh is the SENDER's — `by` — because rows
        // are stored under the peer's id on this side. The old code read `to`
        // (our own id here) and reloaded the wrong conversation, so the bubble
        // never visually changed even when the row was tombstoned.
        val conv = frame.optString("by")
            .ifEmpty { frame.optString("from") }
            .ifEmpty { frame.optString("to") }
        if (conv.isNotEmpty() && conv != sessions.localPeerId()) {
            _messages.value = store.loadMessages(conv)
        } else if (conv.isNotEmpty()) {
            // Fallback: refresh whatever chat is open if the sender field is
            // missing (old relay builds).
            (_screen.value as? Screen.Chat)?.let { _messages.value = store.loadMessages(it.peerId) }
        }
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

    /**
     * One toast per key per minute.
     *
     * A peer that cannot speak the protocol fails *every* message, and without
     * this the UI is a solid wall of identical toasts. Returns true when the
     * caller should show, false when this one should be swallowed.
     */
    private val lastToastAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun shouldToast(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastToastAt[key] ?: 0L
        if (now - last < 60_000L) return false
        lastToastAt[key] = now
        return true
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
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_DURESS_HASH = "duress_hash"
        private const val KEY_PIN_FAILS = "pin_fails"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"

        private const val MIN_PIN_LEN = 4
        private const val MAX_PIN_LEN = 12
        private const val PIN_SALT_LEN = 16
        private const val MAX_PIN_FAILS = 5
        private const val PIN_LOCKOUT_MS = 30_000L
        private const val LOCK_TIMEOUT_MS = 60_000L

        private const val TYPING_TIMEOUT_MS = 4_000L
        private const val TYPING_THROTTLE_MS = 1_500L

        // ------------------------------------------------------------------
        // Disappearing messages
        // ------------------------------------------------------------------

        /** Prefs key prefix: disappearing_<peerId> -> "off" | "seen:<ms>" | "sent:<ms>". */
        private const val KEY_DISAPPEARING_PREFIX = "disappearing_"

        /** New chats default to 2 minutes after seen. */
        private const val DEFAULT_SEEN_MS = 120_000L
        const val DEFAULT_DISAPPEARING = "seen:120000"

        /**
         * Backstop for after-seen timers when no receipt ever arrives. The
         * sender arms sent+max(seen, backstop) and the receiver arms
         * receive+max(seen, backstop), so an unopened message still eventually
         * goes instead of keeping a "disappearing" promise forever. Seven days.
         */
        private const val SEEN_BACKSTOP_MS = 7L * 24L * 60L * 60L * 1000L

        /** How often the expiry sweep runs. */
        private const val EXPIRY_SWEEP_MS = 15_000L

        /** How often the roster is re-polled for presence convergence. */
        private const val ROSTER_POLL_MS = 30_000L

        /** Largest single image accepted for sending, after compression. */
        private const val MAX_IMAGE_BYTES = 2L * 1024L * 1024L

        /** Cap on simultaneous inbound image reassemblies (heap guard). */
        private const val MAX_IMAGE_ASSEMBLIES = 20

        /**
         * How far back the sweep re-sends receipts for the open chat. Ten
         * minutes covers the default 2-minute timer several times over without
         * re-sending for days-old timers every 15 seconds. Older unexpired
         * messages are re-sent on chat open and on peer-online transitions.
         */
        private const val RECEIPT_RESEND_WINDOW_MS = 10L * 60L * 1000L

        /**
         * How far back a reset-triggered re-send reaches. Same ten minutes:
         * the desync case is reinstall-then-chat, and anything older keeps
         * its state rather than being re-sealed under today's timer policy.
         */
        private const val RESET_RESEND_WINDOW_MS = 10L * 60L * 1000L

        /**
         * How far back an offline-error demote reaches. Ten minutes covers a
         * burst sent right before the relay reported the peer gone. Older
         * SENT rows keep their state so a late error cannot resurrect — and
         * re-deliver — history the peer already saw.
         */
        private const val OFFLINE_DEMOTE_WINDOW_MS = 10L * 60L * 1000L

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
