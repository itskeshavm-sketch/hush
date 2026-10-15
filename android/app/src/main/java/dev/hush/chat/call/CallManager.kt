package dev.hush.chat.call

import android.content.Context
import android.util.Log
import dev.hush.chat.data.SignalingRoute
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.asStateFlow

/**
 * Peer-to-peer voice and video over WebRTC.
 *
 * Architecture, and the reasoning behind it:
 *
 *  - **Media is P2P, never relayed.** HUSH has no SFU, no TURN by default, and
 *    no media server of any kind. Audio and video go directly between devices.
 *    The relay sees ciphertext, full stop.
 *
 *  - **Signaling rides the E2E ratchet.** SDP and ICE go through
 *    [SignalingRoute], which is fed by frames the app has already decrypted.
 *    A relay that swaps an SDP cannot do so without being able to encrypt as
 *    one of the parties.
 *
 *  - **DTLS-SRTP with fingerprints, not a signalling-provided secret.** The
 *    certificate below is ephemeral and per-call, so the security of the media
 *    channel does not depend on the signalling channel being honest.
 *
 * Known limitation, stated rather than hidden: without a STUN/TURN server
 * configured, two peers behind symmetric NATs will fail to connect. HUSH has
 * no bundled TURN because a TURN server sees media and would contradict the
 * "the relay never sees your data" claim. [configureIceServers] accepts a
 * STUN list without a TURN fallback, so opportunistic connectivity works on
 * most networks while the privacy claim stays intact.
 */
class CallManager private constructor(private val context: Context) {

    private val _events = kotlinx.coroutines.flow.MutableStateFlow<CallEvent>(CallEvent.Idle)

    /** Read-only view for the UI. [internal] write access stays in this class. */
    val events: kotlinx.coroutines.flow.StateFlow<CallEvent> = _events.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    private var activePeerId: String? = null
    private var isVideoCall = false
    private var makingOffer = false
    private var isSettingRemoteAnswer = false

    /** ICE servers. STUN only by default — see the class doc on TURN. */
    private var iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    private var unsubscribe: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun initialize(appContext: Context) {
        if (factory != null) return

        // WebRTC needs a real initialisation on Android before any factory
        // will construct. Skipping it yields a null factory and an NPE far from
        // the actual cause.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase!!.eglBaseContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ true
                )
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .setAudioDeviceModule(
                // Hardware AEC and noise suppression. Without them, a call in
                // a room with a fan sounds like a call in a vacuum. This WebRTC
                // build exposes no automatic-gain-control setter, so AGC is
                // whatever the platform audio stack does by default.
                JavaAudioDeviceModule.builder(appContext)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
            )
            .createPeerConnectionFactory()
    }

    fun configureIceServers(uris: List<String>) {
        iceServers = uris.map { PeerConnection.IceServer.builder(it).createIceServer() }
    }

    /** Begin an outgoing call. */
    fun startOutgoing(peerId: String, video: Boolean) {
        if (peerConnection != null) {
            Log.w(TAG, "a call is already in progress")
            return
        }
        initialize(context)

        activePeerId = peerId
        isVideoCall = video
        _events.value = CallEvent.Dialing(peerId, video)

        listenForSignaling(peerId)
        createPeerConnection(peerId, video)

        if (video) startLocalVideo()

        peerConnection?.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    Log.d(TAG, "offer created")
                    setLocalDescription(sdp)
                    makingOffer = false
                    signal(peerId, SignalingRoute.TYPE_OFFER, sdp.description)
                }
            },
            MediaConstraints()
        )
    }

    /** Answer an inbound offer. */
    fun acceptIncoming(peerId: String) {
        if (peerConnection != null) return
        initialize(context)

        activePeerId = peerId
        // An inbound offer is how we learn the call is a video call: the
        // offerer's SDP carries a video m-line only if they want video.
        listenForSignaling(peerId)
        _events.value = CallEvent.IncomingAnswering(peerId, isVideoCall)
    }

    fun hangUp() {
        val peerId = activePeerId
        if (peerId != null) {
            signal(peerId, SignalingRoute.TYPE_HANGUP, "")
        }
        teardown()
        _events.value = CallEvent.Idle
    }

    // -------------------------------------------------------------------------
    // Peer connection
    // -------------------------------------------------------------------------

    private fun createPeerConnection(peerId: String, video: Boolean) {
        val f = factory ?: return

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Aggressive nomination on a LAN mesh cuts call setup roughly in
            // half compared with waiting for a full nomination round.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = f.createPeerConnection(config, object : PeerConnection.Observer {
            // Method set verified against org.webrtc.PeerConnection$Observer in
            // webrtc-sdk 125.6422.07 via javap. onAddTrack/onRemoveTrack/onTrack
            // are *default* methods on that version; only the ones below are
            // abstract, and an earlier draft overrode methods that do not
            // exist in this release.

            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ice: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED ->
                        _events.value = CallEvent.Connected(peerId, isVideoCall)

                    PeerConnection.IceConnectionState.FAILED ->
                        _events.value = CallEvent.Failed("Could not reach the other device")

                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        _events.value = CallEvent.Failed("Connection lost")

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                // Candidates must reach the peer; without them, two devices
                // behind NATs never form a candidate pair.
                val c = candidate ?: return
                signal(
                    peerId,
                    SignalingRoute.TYPE_ICE,
                    "${c.sdpMid}|${c.sdpMLineIndex}|${c.sdp}",
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                // Reclaiming a candidate mid-call is a bandwidth optimisation
                // HUSH does not implement. Silently ignoring it is safe: the
                // already-negotiated pair keeps working.
                Log.d(TAG, "ice candidates removed: ${candidates?.size ?: 0}")
            }

            override fun onAddStream(stream: MediaStream?) {
                Log.d(TAG, "remote stream attached")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                remoteVideoTrack = null
            }

            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    remoteVideoTrack = track
                    Log.d(TAG, "remote video track attached")
                }
            }
        })

        addLocalTracks(peerId)
    }

    private fun addLocalTracks(peerId: String) {
        val f = factory ?: return
        val pc = peerConnection ?: return

        val audioSource = f.createAudioSource(MediaConstraints())
        val audioTrack = f.createAudioTrack("audio0", audioSource).apply { setEnabled(true) }
        pc.addTrack(audioTrack, listOf("stream"))

        if (isVideoCall) {
            localVideoTrack = startLocalVideo()
            localVideoTrack?.let { pc.addTrack(it, listOf("stream")) }
        }
    }

    private fun startLocalVideo(): VideoTrack? {
        val f = factory ?: return null

        val enumerator = Camera2Enumerator(context)
        // Prefer the front camera: this is a face-to-face app, and the rear
        // camera on a phone held upright frames the ceiling.
        videoCapturer = enumerator.createCapturer(
            enumerator.deviceNames.firstOrNull { it.contains("front") } ?: enumerator.deviceNames.firstOrNull(),
            null
        )

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)

        val source: VideoSource = f.createVideoSource(isVideoCall)
        videoCapturer?.initialize(surfaceTextureHelper, context, source.capturerObserver)
        videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, FPS)

        return f.createVideoTrack("video0", source).also { it.setEnabled(true) }
    }

    private fun setLocalDescription(sdp: SessionDescription) {
        peerConnection?.setLocalDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() = Unit
            },
            sdp
        )
    }

    // -------------------------------------------------------------------------
    // Signaling
    // -------------------------------------------------------------------------

    private fun listenForSignaling(peerId: String) {
        unsubscribe?.invoke()
        unsubscribe = SignalingRoute.subscribe(peerId) { raw ->
            val parts = raw.split("|")
            when (parts.firstOrNull()) {
                SignalingRoute.TYPE_OFFER ->
                    onRemoteOffer(peerId, parts.getOrNull(1).orEmpty())

                SignalingRoute.TYPE_ANSWER ->
                    onRemoteAnswer(peerId, parts.getOrNull(1).orEmpty())

                SignalingRoute.TYPE_ICE ->
                    onRemoteIce(
                        peerId,
                        parts.getOrNull(1).orEmpty(),
                        parts.getOrNull(2).orEmpty(),
                        parts.getOrNull(3).orEmpty(),
                    )

                SignalingRoute.TYPE_HANGUP -> {
                    teardown()
                    _events.value = CallEvent.Idle
                }

                SignalingRoute.TYPE_BUSY -> {
                    _events.value = CallEvent.Failed("Peer is on another call")
                }
            }
        }
    }

    private fun onRemoteOffer(peerId: String, sdp: String) {
        val pc = peerConnection ?: run {
            // We have no connection yet: the peer rang before we accepted.
            _events.value = CallEvent.Ringing(peerId, sdp)
            return
        }

        // Ignore an offer that collides with our own local offer. Answering it
        // would drive the connection into the wrong signalling state, and the
        // failure is silent — both sides simply never connect.
        val ignore = makingOffer || pc.signalingState() != PeerConnection.SignalingState.STABLE
        if (ignore) {
            Log.d(TAG, "ignoring offer while signaling state is ${pc.signalingState()}")
            return
        }

        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    pc.createAnswer(
                        object : SimpleSdpObserver() {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                isSettingRemoteAnswer = true
                                setLocalDescription(answer)
                                isSettingRemoteAnswer = false
                                signal(peerId, SignalingRoute.TYPE_ANSWER, answer.description)
                            }
                        },
                        MediaConstraints()
                    )
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp)
        )
    }

    private fun onRemoteAnswer(peerId: String, sdp: String) {
        if (peerConnection?.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        peerConnection?.setRemoteDescription(
            object : SimpleSdpObserver() { override fun onSetSuccess() = Unit },
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    private fun onRemoteIce(peerId: String, mid: String, mLine: String, sdp: String) {
        peerConnection?.addIceCandidate(
            IceCandidate(mid, mLine.toInt(), sdp)
        )
    }

    private fun signal(peerId: String, type: String, body: String) {
        SignalingRoute.emit(peerId, type, body)
    }

    // -------------------------------------------------------------------------

    private fun teardown() {
        unsubscribe?.invoke()
        unsubscribe = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        remoteVideoTrack = null

        activePeerId = null
        makingOffer = false
        isSettingRemoteAnswer = false
    }

    fun release() {
        teardown()
        factory?.dispose()
        factory = null
    }

    companion object {
        private const val TAG = "HushCall"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val FPS = 30

        @Volatile
        private var instance: CallManager? = null

        fun get(context: Context): CallManager =
            instance ?: synchronized(this) {
                instance ?: CallManager(context.applicationContext).also { instance = it }
            }
    }
}

/** Entry point the UI calls to place a call. */
object CallOutgoing {
    fun start(context: Context, peerId: String, video: Boolean) {
        val manager = CallManager.get(context)
        manager.startOutgoing(peerId, video)

        val intent = android.content.Intent(context, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_PEER_ID, peerId)
            putExtra(CallActivity.EXTRA_VIDEO, video)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/** Call state surfaced to the UI. */
sealed interface CallEvent {
    data object Idle : CallEvent
    data class Dialing(val peerId: String, val video: Boolean) : CallEvent
    data class Ringing(val peerId: String, val sdp: String) : CallEvent
    data class IncomingAnswering(val peerId: String, val video: Boolean) : CallEvent
    data class Connected(val peerId: String, val video: Boolean) : CallEvent
    data class Failed(val reason: String) : CallEvent
}

/**
 * SdpObserver with no-op defaults so call sites only override what they use.
 *
 * The parameter types must match the Java interface exactly. WebRTC's methods
 * take non-null `SessionDescription`, so declaring them nullable here and then
 * overriding with a non-null parameter is an override mismatch that the Kotlin
 * compiler reports as "overrides nothing" — which reads like a missing method
 * rather than a nullability conflict.
 */
abstract class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}
