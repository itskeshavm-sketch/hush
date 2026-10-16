package dev.hush.chat.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/** Connection state, surfaced directly in the UI. */
sealed interface RelayState {
    data object Idle : RelayState
    data object Connecting : RelayState
    data object Connected : RelayState

    /** Retrying. [attempt] is 1-based; [nextRetryInMs] drives the countdown. */
    data class Reconnecting(val attempt: Int, val nextRetryInMs: Long, val cause: String) : RelayState

    /**
     * Terminal. A failed pin is deliberately not retried: retrying a pin
     * failure forever would leave the UI spinning on a connection that can
     * never succeed.
     */
    data class Failed(val reason: String, val recoverable: Boolean) : RelayState
}

/** A peer as published by the relay roster. */
data class PeerInfo(
    val id: String,
    val name: String,
    val identityPublic: ByteArray,
    val prekeyJson: String?,
    val bio: String,
    val joinedAt: Long,
    val online: Boolean,
) {
    /** Stable colour seed so an avatar keeps the same tint across restarts. */
    val avatarSeed: Int get() = id.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }

    override fun equals(other: Any?): Boolean = other is PeerInfo && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** An inbound ciphertext, addressed to us, not yet decrypted. */
data class InboundEnvelope(
    val from: String,
    val fromName: String,
    val kind: String,
    val payload: ByteArray,
    val serverAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is InboundEnvelope && other.from == from && other.kind == kind && other.payload.contentEquals(payload)

    override fun hashCode(): Int = (from.hashCode() * 31 + kind.hashCode()) * 31 + payload.contentHashCode()
}

/**
 * The relay WebSocket client.
 *
 * Responsibilities, and deliberately nothing else:
 *   - hold exactly one socket, reconnecting with exponential backoff
 *   - speak the HUSH wire protocol
 *   - surface frames as flows for the repository to consume
 *
 * It does not decrypt, does not persist, and does not know what a message is.
 * Everything crossing this boundary is an opaque byte array.
 */
class RelayClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<JSONObject>(replay = 0, extraBufferCapacity = 256)
    val frames: SharedFlow<JSONObject> = _frames.asSharedFlow()

    private val _roster = MutableStateFlow<List<PeerInfo>>(emptyList())
    val roster: StateFlow<List<PeerInfo>> = _roster.asStateFlow()

    private val _pinnedFingerprint = MutableStateFlow<String?>(null)
    val pinnedFingerprint: StateFlow<String?> = _pinnedFingerprint.asStateFlow()

    private var socket: WebSocket? = null
    private var hello: JSONObject? = null
    private var client: OkHttpClient? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    @Volatile
    private var endpoint: String? = null

    @Volatile
    private var pin: String? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Connect, or reconnect to a different relay.
     *
     * @param wssUrl the relay WebSocket endpoint
     * @param pinnedFingerprintHex stored SHA-256 pin, or null to trust the
     *   system CA store (used on first contact to capture a self-signed cert)
     * @param helloFrame the `hello` payload replayed on every (re)connect
     */
    fun connect(wssUrl: String, pinnedFingerprintHex: String?, helloFrame: JSONObject) {
        endpoint = wssUrl
        pin = pinnedFingerprintHex
        hello = helloFrame
        attempt = 0
        reconnectJob?.cancel()
        openSocket()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        helloAcked = false
        socket?.close(NORMAL_CLOSURE, "client disconnect")
        socket = null
        _state.value = RelayState.Idle
        _roster.value = emptyList()
    }

    /**
     * Called the first time a relay's certificate is seen, so the app can
     * persist it. Without this the pin is re-derived on every launch and
     * "trust on first use" silently degrades into "trust always", which is the
     * thing pinning exists to prevent.
     */
    var onPinObserved: ((String) -> Unit)? = null

    /** Re-baseline the pin against the certificate currently being served. */
    fun updatePin(fingerprintHex: String) {
        pin = fingerprintHex
        // A changed pin invalidates the cached client: OkHttp bakes the trust
        // manager into the SSL socket factory at construction, so reusing it
        // would keep enforcing the OLD pin and fail forever.
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
        client = null

        val url = endpoint
        val frame = hello
        if (url != null && frame != null) {
            attempt = 0
            reconnectJob?.cancel()
            openSocket()
        }
    }

    // -------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------

    /**
     * Whether the relay has acknowledged our hello on this socket.
     *
     * Frames sent after a reconnect but before the welcome are rejected with
     * "send hello first" — a raw relay error that used to surface as a toast
     * and, worse, burned the send (the message was marked failed for a
     * handshake race, not a real failure). Gating here turns those sends into
     * quiet `false`s, so the caller queues instead of alarming.
     */
    @Volatile
    var helloAcked: Boolean = false
        private set

    /**
     * Queue a frame. Returns false if the socket is not currently usable —
     * unwritable, or connected but not yet welcomed — and the caller decides
     * whether that is a user-visible error or a silent drop.
     */
    fun send(frame: JSONObject): Boolean {
        if (!helloAcked) return false
        return socket?.send(frame.toString()) ?: false
    }

    fun sendMessage(to: String, sealedPayload: ByteArray) = send(
        JSONObject()
            .put("t", "send")
            .put("to", to)
            .put("envelope", java.util.Base64.getEncoder().encodeToString(sealedPayload))
    )

    fun sendRtc(to: String, sealedPayload: ByteArray) = send(
        JSONObject()
            .put("t", "rtc")
            .put("to", to)
            .put("envelope", java.util.Base64.getEncoder().encodeToString(sealedPayload))
    )

    /** Delete for both sides. There is no edit path anywhere in HUSH. */
    fun sendDelete(to: String, messageId: String) = send(
        JSONObject().put("t", "delete").put("to", to).put("msgId", messageId).put("forAll", true)
    )

    /**
     * Read receipt and keep notice. Fire-and-forget like typing: if the peer
     * is offline the relay drops it, and the ViewModel re-sends unacked
     * receipts when the peer reappears. No error surfacing — a receipt is
     * retryable bookkeeping, not a message.
     */
    fun sendSeen(to: String, messageId: String) = send(
        JSONObject().put("t", "seen").put("to", to).put("msgId", messageId)
    )

    fun sendKept(to: String, messageId: String) = send(
        JSONObject().put("t", "kept").put("to", to).put("msgId", messageId)
    )

    /**
     * Ask a peer to drop their ratchet session for us. Sent (throttled) when
     * their messages stop decrypting: they keep encrypting on a stale session
     * while we fail every frame, and nothing heals until THEY re-handshake.
     * The next message from either side establishes a fresh X3DH session.
     */
    fun sendReset(to: String) = send(
        JSONObject().put("t", "reset").put("to", to)
    )

    fun sendTyping(to: String, active: Boolean) = send(
        JSONObject().put("t", "typing").put("to", to).put("state", active)
    )

    fun sendProfile(bio: String) = send(JSONObject().put("t", "profile").put("bio", bio))

    /**
     * Ask the relay for a fresh unicast roster snapshot.
     *
     * Broadcasts can be missed — a frame dropped on a flaky socket is gone,
     * and the next broadcast only comes on the next join/leave. Polling this
     * every ~30s (see the ViewModel) bounds any staleness: the view converges
     * to the truth even if every broadcast in between was lost. The server
     * answers only the requester, so this costs one small frame each way.
     */
    fun refreshRoster() = send(JSONObject().put("t", "roster"))

    // -------------------------------------------------------------------------
    // Socket management
    // -------------------------------------------------------------------------

    private fun openSocket() {
        val url = endpoint ?: return
        _state.value = if (attempt == 0) RelayState.Connecting else _state.value

        val http = buildClient()
        val request = Request.Builder().url(url).build()

        socket = http.newWebSocket(request, Listener())
    }

    private fun buildClient(): OkHttpClient {
        client?.let { return it }

        // Trust on first use. With no recorded pin the relay's certificate is
        // accepted and observed; the app persists that fingerprint so every
        // later connection must present exactly the same one.
        //
        // A plain `CertificatePinner` cannot do this job: pinning only runs
        // *after* the chain validates against the system trust store, and a
        // self-signed relay never gets that far. The failure mode of getting
        // this wrong is an app that cannot connect to any self-hosted relay on
        // the first try, with the fingerprint capture code unreachable.
        val trustManager = TofuTrustManager(
            pinnedHex = pin,
            onObserved = { fp ->
                _pinnedFingerprint.value = fp
                if (pin == null) {
                    pin = fp
                    onPinObserved?.invoke(fp)
                }
            },
        )

        val sslContext = buildSslContext(trustManager)

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(sslContext.socketFactory, trustManager)

        return builder.build().also { client = it }
    }

    private fun url(): String = endpoint ?: throw IllegalStateException("not connected")

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            helloAcked = false
            Log.i(TAG, "relay socket open")

            // The trust manager already observed the certificate during the
            // handshake; this is belt-and-braces for a chain that validated
            // through the system store instead.
            response.handshake?.peerCertificates
                ?.filterIsInstance<java.security.cert.X509Certificate>()
                ?.takeIf { it.isNotEmpty() }
                ?.let { chain ->
                    TlsPinner.fingerprintHex(chain)?.let { fp ->
                        _pinnedFingerprint.value = fp
                        if (pin == null) pin = fp
                    }
                }

            // Must be serialised here: WebSocket.send takes a String or a
            // ByteString, and `let(webSocket::send)` hands it a JSONObject.
            // Hello bypasses the helloAcked gate — it IS the handshake.
            hello?.let { webSocket.send(it.toString()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // getOrNull, not getOrElse: the recover branch returns Unit and the
            // success branch returns a JSONObject, which collapses the inferred
            // type to Any and makes every downstream use unresolvable.
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: run {
                Log.w(TAG, "dropping unparseable frame of ${text.length} chars")
                return
            }

            when (frame.optString("t")) {
                "welcome" -> {
                    helloAcked = true
                    _state.value = RelayState.Connected
                    frame.optJSONArray("peers")?.let { _roster.value = it.toPeerList() }
                }
                "roster" -> frame.optJSONArray("peers")?.let { _roster.value = it.toPeerList() }
                else -> Unit
            }

            // Fan out everything; the repository decides what matters.
            _frames.tryEmit(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "relay closing: $code $reason")
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            helloAcked = false
            _state.value = RelayState.Idle
            if (code != NORMAL_CLOSURE) scheduleReconnect(reason.ifEmpty { "closed ($code)" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            helloAcked = false
            val message = t.message ?: t.javaClass.simpleName

            // A pin failure is terminal by design. Retrying a handshake that
            // cannot succeed just burns battery and hides the problem behind a
            // spinner that never resolves.
            if (t is java.security.cert.CertificateException) {
                _state.value = RelayState.Failed(
                    reason = message.ifEmpty { "The relay's certificate did not match the one recorded." },
                    recoverable = false,
                )
                Log.e(TAG, "certificate rejected", t)
                return
            }

            Log.w(TAG, "relay failure: $message")
            scheduleReconnect(message)
        }
    }

    /**
     * Exponential backoff with full jitter.
     *
     * The jitter is not decoration: without it, every client dropped by a relay
     * restart reconnects in lockstep and re-creates the thundering herd that
     * caused the drop.
     */
    private fun scheduleReconnect(cause: String) {
        if (reconnectJob?.isActive == true) return

        attempt++
        val ceiling = min(MAX_BACKOFF_MS, (BASE_BACKOFF_MS * 2.0.pow(attempt - 1)).toLong())
        val delayMs = (ceiling * (0.5 + Math.random() * 0.5)).toLong()

        _state.value = RelayState.Reconnecting(attempt, delayMs, cause)

        reconnectJob = scope.launch {
            delay(delayMs)
            if (isActive) openSocket()
        }
    }

    // -------------------------------------------------------------------------

    private fun org.json.JSONArray.toPeerList(): List<PeerInfo> {
        val out = ArrayList<PeerInfo>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out += PeerInfo(
                id = id,
                name = o.optString("name").ifEmpty { "unknown" },
                identityPublic = o.optString("identityPublic").runCatching {
                    java.util.Base64.getDecoder().decode(this)
                }.getOrDefault(ByteArray(0)),
                prekeyJson = o.optString("prekey").takeIf { it.isNotEmpty() && it != "null" },
                bio = o.optString("bio"),
                joinedAt = o.optLong("joinedAt"),
                online = o.optBoolean("online", true),
            )
        }
        // Self is included in the roster frame; the UI renders "All Users", so
        // filter it out here rather than in three different screens.
        return out
    }

    companion object {
        private const val TAG = "HushRelay"
        private const val NORMAL_CLOSURE = 1000
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
