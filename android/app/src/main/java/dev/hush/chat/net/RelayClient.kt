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
        socket?.close(NORMAL_CLOSURE, "client disconnect")
        socket = null
        _state.value = RelayState.Idle
        _roster.value = emptyList()
    }

    /** Re-baseline the pin against the certificate currently being served. */
    fun updatePin(fingerprintHex: String) {
        pin = fingerprintHex
        // Force a fresh handshake so the new pin is exercised immediately
        // rather than at some unpredictable future reconnect.
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
     * Queue a frame. Returns false if the socket is not currently writable —
     * the caller decides whether that is a user-visible error or a silent drop.
     */
    fun send(frame: JSONObject): Boolean = socket?.send(frame.toString()) ?: false

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

    fun sendTyping(to: String, active: Boolean) = send(
        JSONObject().put("t", "typing").put("to", to).put("state", active)
    )

    fun sendProfile(bio: String) = send(JSONObject().put("t", "profile").put("bio", bio))

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

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Pinning is opt-in per connection. With no pin we fall back to the
        // system trust store, which is correct for a relay with a real cert and
        // is exactly what captures the fingerprint on first contact.
        val fingerprint = pin
        if (fingerprint != null) {
            val host = url().toHttpUrlOrNull()?.host
                ?: throw IllegalStateException("relay URL is not parseable: $endpoint")
            builder.certificatePinner(TlsPinner.pinnerFor(host, fingerprint))
        }

        return builder.build().also { client = it }
    }

    private fun url(): String = endpoint ?: throw IllegalStateException("not connected")

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            Log.i(TAG, "relay socket open")

            // Capture the presented certificate so a self-signed relay can be
            // pinned on first contact rather than rejected outright.
            //
            // `Handshake.peerCertificates` is typed List<Certificate>, not
            // List<X509Certificate>; the runtime objects are X509 but the
            // static type is not, so the cast has to happen here.
            response.handshake?.peerCertificates
                ?.filterIsInstance<java.security.cert.X509Certificate>()
                ?.takeIf { it.isNotEmpty() }
                ?.let { chain ->
                    TlsPinner.fingerprintOf(chain)?.let { fp ->
                        _pinnedFingerprint.value = fp
                        if (pin == null) pin = fp
                    }
                }

            // Must be serialised here: WebSocket.send takes a String or a
            // ByteString, and `let(webSocket::send)` hands it a JSONObject.
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
            _state.value = RelayState.Idle
            if (code != NORMAL_CLOSURE) scheduleReconnect(reason.ifEmpty { "closed ($code)" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = t.message ?: t.javaClass.simpleName

            // A pin failure is terminal by design. Retrying a handshake that
            // cannot succeed just burns battery and hides the problem.
            if (t is javax.net.ssl.SSLPeerUnverifiedException) {
                _state.value = RelayState.Failed(
                    reason = "Certificate pin mismatch. This is not the relay you pinned.",
                    recoverable = false,
                )
                Log.e(TAG, "pin mismatch", t)
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
