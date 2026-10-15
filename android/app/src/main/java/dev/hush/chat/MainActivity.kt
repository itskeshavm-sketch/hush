package dev.hush.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hush.chat.ui.ChatScreen
import dev.hush.chat.ui.OnboardingScreen
import dev.hush.chat.ui.RosterScreen
import dev.hush.chat.ui.SafetyNumberScreen
import dev.hush.chat.ui.SearchScreen
import dev.hush.chat.ui.SettingsScreen
import dev.hush.chat.ui.theme.HushTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Audio/camera are requested at call time, not here; see CallManager. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            HushRoot()
        }
    }
}

@Composable
private fun HushRoot(viewModel: HushViewModel = viewModel()) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val searchHits by viewModel.searchHits.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val typingIn by viewModel.typingIn.collectAsStateWithLifecycle()
    val relayState by viewModel.relayState.collectAsStateWithLifecycle()
    val fingerprint by viewModel.pinnedFingerprint.collectAsStateWithLifecycle()

    val themeKey by viewModel.themeKey.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it.text)
            viewModel.consumeToast()
        }
    }

    HushTheme(themeKey = themeKey) {
    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { _ ->
        when (val s = screen) {
            is Screen.Onboarding -> OnboardingScreen(
                initialRelay = viewModel.relayUrl,
                onContinue = { name, relay -> viewModel.completeOnboarding(name, relay) },
            )

            is Screen.Roster -> RosterScreen(
                peers = peers,
                displayName = viewModel.displayName,
                relayState = relayState,
                onOpenPeer = { viewModel.openChat(it.id) },
                onOpenSearch = { viewModel.navigate(Screen.Search) },
                onOpenSettings = { viewModel.navigate(Screen.Settings) },
                onRetryRelay = { viewModel.navigate(Screen.Roster) },
            )

            is Screen.Chat -> {
                val peer = peers.firstOrNull { it.id == s.peerId }
                val context = LocalContext.current
                ChatScreen(
                    peerName = s.peerName,
                    peerSeed = s.peerId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF },
                    peerOnline = peer?.online ?: false,
                    peerVerified = peer?.verified ?: false,
                    messages = messages,
                    peerTyping = s.peerId in typingIn,
                    onBack = { viewModel.navigate(Screen.Roster) },
                    onSend = { viewModel.sendMessage(s.peerId, it) },
                    onTyping = { viewModel.onTyping(s.peerId) },
                    onDelete = { viewModel.deleteForEveryone(s.peerId, it.id) },
                    onRetry = { viewModel.retryMessage(s.peerId, it.id) },
                    onVoiceCall = { dev.hush.chat.call.CallOutgoing.start(context, s.peerId, video = false) },
                    onVideoCall = { dev.hush.chat.call.CallOutgoing.start(context, s.peerId, video = true) },
                    onVerify = { viewModel.navigate(Screen.SafetyNumber(s.peerId, s.peerName)) },
                )
            }

            is Screen.Search -> SearchScreen(
                query = searchHits.takeIf { it.isNotEmpty() }?.let { "" } ?: "",
                hits = searchHits,
                onQueryChange = viewModel::search,
                onOpenHit = { viewModel.jumpToHit(it) },
                onBack = {
                    viewModel.clearSearch()
                    viewModel.navigate(Screen.Roster)
                },
            )

            is Screen.Settings -> SettingsScreen(
                displayName = viewModel.displayName,
                themeKey = themeKey,
                relayFingerprint = fingerprint,
                onDisplayNameChange = viewModel::changeDisplayName,
                onThemeChange = viewModel::setTheme,
                onTrustNewCertificate = viewModel::trustNewRelayCertificate,
                onBack = { viewModel.navigate(Screen.Roster) },
            )

            is Screen.SafetyNumber -> SafetyNumberScreen(
                peerName = s.peerName,
                peerSeed = s.peerId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF },
                safetyNumber = viewModel.safetyNumberFor(s.peerId),
                verified = peers.firstOrNull { it.id == s.peerId }?.verified ?: false,
                onVerify = { viewModel.setVerified(s.peerId, true) },
                onBack = { viewModel.navigate(Screen.Chat(s.peerId, s.peerName)) },
            )

            is Screen.PeerInfo -> EmptyPeerScreen(s.peerName) {
                viewModel.navigate(Screen.Roster)
            }
        }
    }
    }
}

@Composable
private fun EmptyPeerScreen(peerName: String, onBack: () -> Unit) {
    // Placeholder route kept so navigation targets stay total. Reachable only
    // if a future feature routes here; the roster opens the chat directly.
    androidx.compose.material3.Button(onClick = onBack) {
        androidx.compose.material3.Text("Back from $peerName")
    }
}
