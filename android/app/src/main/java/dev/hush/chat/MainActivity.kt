package dev.hush.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hush.chat.ui.ChatScreen
import dev.hush.chat.ui.CreateGroupScreen
import dev.hush.chat.ui.GroupInfoScreen
import dev.hush.chat.ui.OnboardingScreen
import dev.hush.chat.ui.RosterScreen
import dev.hush.chat.ui.SafetyNumberScreen
import dev.hush.chat.ui.SearchScreen
import dev.hush.chat.ui.LockScreen
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
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val searchHits by viewModel.searchHits.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val typingIn by viewModel.typingIn.collectAsStateWithLifecycle()
    val relayState by viewModel.relayState.collectAsStateWithLifecycle()
    val fingerprint by viewModel.pinnedFingerprint.collectAsStateWithLifecycle()

    val themeKey by viewModel.themeKey.collectAsStateWithLifecycle()

    // Relock when the app has been backgrounded past the timeout. ON_PAUSE
    // fires for the recents screen too, which is exactly the surface a lock
    // exists for.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it.text)
            viewModel.consumeToast()
        }
    }

    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val pinSet by viewModel.pinSet.collectAsStateWithLifecycle()
    val duressSet by viewModel.duressSet.collectAsStateWithLifecycle()

    HushTheme(themeKey = themeKey) {
    // A locked phone shows nothing else — not the roster, not a preview.
    // The overlay sits above the scaffold so no screen underneath can leak.
    if (locked) {
        LockScreen(onUnlock = viewModel::unlock)
    } else {
    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { _ ->
        // Screens cross-fade with a short lateral drift. Fast enough to feel
        // instant, present enough to feel spatial: back goes right, forward
        // goes left. AnimatedContent keys on the Screen value, so a peer
        // switch inside Chat re-runs it — which is correct, it is a
        // different place.
        AnimatedContent(
            targetState = screen,
            label = "screen",
            transitionSpec = {
                (fadeIn(animationSpec = tween(180)) +
                    slideInHorizontally(animationSpec = tween(180)) { it / 6 }) togetherWith
                    (fadeOut(animationSpec = tween(140)) +
                        slideOutHorizontally(animationSpec = tween(140)) { -it / 6 })
            },
        ) { target ->
        when (val s = target) {
            is Screen.Onboarding -> OnboardingScreen(
                initialRelay = viewModel.relayUrl,
                onContinue = { name, relay -> viewModel.completeOnboarding(name, relay) },
            )

            is Screen.Roster -> RosterScreen(
                peers = peers,
                groups = groups,
                displayName = viewModel.displayName,
                relayState = relayState,
                onOpenPeer = { viewModel.openChat(it.id) },
                onOpenGroup = { viewModel.openGroup(it.id) },
                onCreateGroup = { viewModel.navigate(Screen.NewGroup) },
                onOpenSearch = { viewModel.navigate(Screen.Search) },
                onOpenSettings = { viewModel.navigate(Screen.Settings) },
                onRetryRelay = { viewModel.navigate(Screen.Roster) },
            )

            is Screen.Chat -> {
                val peer = peers.firstOrNull { it.id == s.peerId }
                val context = LocalContext.current
                var disappearing by remember(s.peerId) { mutableStateOf(viewModel.disappearingFor(s.peerId)) }
                ChatScreen(
                    peerName = s.peerName,
                    peerSeed = s.peerId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF },
                    peerVerified = peer?.verified ?: false,
                    messages = messages,
                    peerTyping = s.peerId in typingIn,
                    disappearingValue = disappearing,
                    disappearingLabel = viewModel.disappearingLabel(disappearing),
                    onSetDisappearing = {
                        viewModel.setDisappearing(s.peerId, it)
                        disappearing = it
                    },
                    onBack = { viewModel.navigate(Screen.Roster) },
                    onSend = { viewModel.sendMessage(s.peerId, it) },
                    onTyping = { viewModel.onTyping(s.peerId) },
                    onDelete = { viewModel.deleteForEveryone(s.peerId, it.id) },
                    onKeep = { viewModel.keepMessage(s.peerId, it.id) },
                    onSendImage = { viewModel.sendImageRaw(s.peerId, it) },
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
                pinSet = pinSet,
                duressSet = duressSet,
                relayFingerprint = fingerprint,
                relayUrl = viewModel.relayUrl,
                onDisplayNameChange = viewModel::changeDisplayName,
                onThemeChange = viewModel::setTheme,
                onSetRelayUrl = viewModel::setRelayUrl,
                onSetAppPin = viewModel::setAppPin,
                onSetDuressPin = viewModel::setDuressPin,
                onVerifyPin = viewModel::verifyPin,
                onRemoveLock = viewModel::removePins,
                onLockNow = viewModel::lockNow,
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

            is Screen.Group -> {
                val group = groups.firstOrNull { it.id == s.groupId }
                var disappearing by remember(s.groupId) { mutableStateOf(viewModel.disappearingFor(s.groupId)) }
                ChatScreen(
                    peerName = group?.name ?: s.groupName,
                    peerSeed = s.groupId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF },
                    peerVerified = false,
                    messages = messages,
                    peerTyping = false,
                    disappearingValue = disappearing,
                    disappearingLabel = viewModel.disappearingLabel(disappearing),
                    onSetDisappearing = {
                        viewModel.setDisappearing(s.groupId, it)
                        disappearing = it
                    },
                    onBack = { viewModel.navigate(Screen.Roster) },
                    onSend = { viewModel.sendGroupText(s.groupId, it) },
                    onTyping = {},
                    onDelete = { viewModel.deleteGroupMessageForEveryone(s.groupId, it.id) },
                    onKeep = { viewModel.keepGroupMessage(it.id) },
                    onSendImage = {},
                    onRetry = { viewModel.retryMessage(s.groupId, it.id) },
                    onVoiceCall = {},
                    onVideoCall = {},
                    onVerify = {},
                    isGroup = true,
                    groupSubtitle = group?.let { "${it.memberIds.size} members" },
                    showSenderNames = true,
                    onOpenGroupInfo = { viewModel.navigate(Screen.GroupDetail(s.groupId)) },
                    callsEnabled = false,
                    imagesEnabled = false,
                )
            }

            is Screen.NewGroup -> CreateGroupScreen(
                peers = peers,
                onBack = { viewModel.navigate(Screen.Roster) },
                onCreate = { name, members ->
                    viewModel.createGroup(name, members)
                },
            )

            is Screen.GroupDetail -> {
                val group = groups.firstOrNull { it.id == s.groupId }
                if (group == null) {
                    LaunchedEffect(Unit) { viewModel.navigate(Screen.Roster) }
                } else {
                    GroupInfoScreen(
                        group = group,
                        peers = peers,
                        selfId = viewModel.selfId,
                        onBack = { viewModel.openGroup(s.groupId) },
                        onAddMembers = { viewModel.addGroupMembers(s.groupId, it) },
                        onRemoveMember = { viewModel.removeGroupMember(s.groupId, it) },
                        onRename = { viewModel.renameGroup(s.groupId, it) },
                        onLeave = {
                            viewModel.leaveGroup(s.groupId)
                        },
                    )
                }
            }
        }
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
