package dev.hush.chat.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hush.chat.ui.components.PulsingDot
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-screen call UI.
 *
 * The video surface itself is rendered by [CallService]'s `SurfaceView`, which
 * sits behind this Compose layer. Keeping the Compose tree free of the video
 * renderer means re-composition during a call does not tear down and rebuild
 * the GL surface, which is the usual cause of a black screen after rotation.
 */
class CallActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val mic = grants[Manifest.permission.RECORD_AUDIO] == true
        val camera = grants[Manifest.permission.CAMERA] == true
        if (!mic) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }

        setContent {
            HushCallTheme {
                CallScreen(
                    peerId = peerId,
                    video = video,
                    onHangUp = { CallManager.get(this).hangUp(); finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_VIDEO = "video"
    }
}

@Composable
private fun HushCallTheme(content: @Composable () -> Unit) {
    // The call screen is always dark: it may be showing a video feed, and a
    // light chrome around one looks broken.
    dev.hush.chat.ui.theme.HushTheme(darkTheme = true, content = content)
}

@Composable
private fun CallScreen(
    peerId: String,
    video: Boolean,
    onHangUp: () -> Unit,
) {
    val manager = CallManager.get(androidx.compose.ui.platform.LocalContext.current)
    val event = manager.events.collectAsStateSafe()

    var muted by remember { mutableStateOf(false) }
    var cameraOff by remember { mutableStateOf(false) }

    val statusText = when (val e = event) {
        is CallEvent.Dialing -> "Calling…"
        is CallEvent.Ringing -> "Incoming call"
        is CallEvent.IncomingAnswering -> "Connecting…"
        is CallEvent.Connected -> "Connected"
        is CallEvent.Failed -> e.reason
        else -> "Call ended"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Remote video would be composited here behind this layer. Until a
        // remote track is attached, show the peer identifier so the screen is
        // never an undifferentiated black rectangle.
        if (!video || event !is CallEvent.Connected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(HushSurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = peerId.take(2).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = HushTextSecondary,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                if (event is CallEvent.Dialing || event is CallEvent.IncomingAnswering) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(color = HushTextSecondary, animate = true, size = 6.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Peer-to-peer · relay carries signaling only",
                            style = MaterialTheme.typography.bodySmall,
                            color = HushTextTertiary,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val failure = event as? CallEvent.Failed
            if (failure != null) {
                Text(
                    text = failure.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = HushDanger,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallButton(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (muted) "Unmute" else "Mute",
                    tint = if (muted) HushDanger else Color.White,
                    onClick = { muted = !muted },
                )
                CallButton(
                    icon = Icons.Filled.CallEnd,
                    label = "End",
                    tint = Color.White,
                    background = HushDanger,
                    onClick = onHangUp,
                )
                CallButton(
                    icon = if (cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    label = "Camera",
                    tint = if (cameraOff) HushDanger else Color.White,
                    onClick = { cameraOff = !cameraOff },
                )
            }
        }
    }
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    background: Color = HushSurfaceElevated,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = HushTextTertiary)
    }
}

/**
 * `collectAsStateWithLifecycle` lives in `lifecycle-runtime-compose` and needs
 * a LifecycleOwner, which an Activity's setContent always has. This wrapper
 * returns the value rather than a `State<>` so call sites read naturally, and
 * gives us one place to change if the call screen ever moves to a non-Activity
 * host.
 */
@Composable
private fun <T> StateFlow<T>.collectAsStateSafe(): T = this.collectAsStateWithLifecycle().value
