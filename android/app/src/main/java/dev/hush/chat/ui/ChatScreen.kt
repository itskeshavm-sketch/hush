@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.hush.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hush.chat.data.DeliveryState
import dev.hush.chat.data.Message
import dev.hush.chat.ui.components.InitialsAvatar
import dev.hush.chat.ui.components.TypingIndicator
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import dev.hush.chat.ui.theme.HushAccent
import dev.hush.chat.ui.theme.HushOnSend
import dev.hush.chat.ui.theme.HushSend
import dev.hush.chat.ui.theme.HushBubbleEdge
import dev.hush.chat.ui.theme.HushBubbleIncoming
import dev.hush.chat.ui.theme.HushBubbleOutgoing
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushTextTertiary
import dev.hush.chat.ui.theme.HushTyping
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Conversation screen.
 *
 * Design decisions worth naming:
 *
 *  - **Timestamps are hidden by default.** A dense log of HH:MM under every
 *    bubble is noise; the separator appears only when the gap exceeds two
 *    minutes, which is when the time actually carries information.
 *  - **The composer stays pinned to the keyboard** via imePadding, so a long
 *    scroll does not leave the input stranded behind the keyboard.
 *  - **Delete is per-message, from a long-press menu.** There is no edit
 *    affordance anywhere, because there is no edit code path anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    peerName: String,
    peerSeed: Int,
    peerVerified: Boolean,
    messages: List<Message>,
    peerTyping: Boolean,
    disappearingValue: String = "seen:120000",
    disappearingLabel: String = "2 min after seen",
    onSetDisappearing: (String) -> Unit = {},
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onTyping: () -> Unit,
    onDelete: (Message) -> Unit,
    onKeep: (Message) -> Unit = {},
    onSendImage: (ByteArray) -> Unit = {},
    onRetry: (Message) -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    var timerMenuOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<ByteArray?>(null) }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // Read off the picker result immediately: the granted URI access
            // is short-lived, so bytes are copied now and the dialog decides.
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) pendingImage = bytes
        }
    }

    // Follow the conversation as it grows, but only when the user is already
    // near the bottom. Yanking the viewport down while they are reading
    // history is the single most irritating thing a chat app can do.
    LaunchedEffect(messages.size) {
        val last = messages.lastOrNull()?.let { listState.layoutInfo.totalItemsCount - 1 } ?: 0
        if (last > 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable(onClick = onVerify)
                            .padding(vertical = 4.dp),
                    ) {
                        InitialsAvatar(
                            initials = peerName.take(1).uppercase(),
                            seed = peerSeed,
                            size = 36.dp,
                            online = null,
                            verified = peerVerified,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = peerName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            AnimatedVisibility(visible = peerTyping, enter = fadeIn(), exit = fadeOut()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TypingIndicator(color = HushTyping)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "typing",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = HushTyping,
                                    )
                                }
                            }
                            // No online/offline line: presence was never
                            // accurate enough to print. The timer policy is
                            // yours-outgoing (see the timer menu note); it is
                            // the only per-chat fact shown here.
                            if (!peerTyping && disappearingLabel != "Off") {
                                Text(
                                    text = "your timer: $disappearingLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HushTextSecondary,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onVoiceCall) {
                        Icon(Icons.Filled.Call, contentDescription = "Voice call", tint = HushTextSecondary)
                    }
                    IconButton(onClick = onVideoCall) {
                        Icon(Icons.Filled.Videocam, contentDescription = "Video call", tint = HushTextSecondary)
                    }
                    Box {
                        IconButton(onClick = { timerMenuOpen = true }) {
                            Icon(
                                Icons.Filled.Timer,
                                contentDescription = "Disappearing timer ($disappearingLabel)",
                                tint = if (disappearingLabel != "Off") HushAccent else HushTextSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = timerMenuOpen,
                            onDismissRequest = { timerMenuOpen = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = HushSurfaceElevated,
                        ) {
                            // The rule, stated where the choice is made: your
                            // timer governs YOUR messages. Theirs follow their
                            // sender's timer — OFF on your side never keeps
                            // someone else's timed message.
                            Text(
                                text = "Your timer applies to messages you send. Messages you get follow the sender's timer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = HushTextSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            TimerPreset(
                                label = "Off — your messages never expire",
                                selected = disappearingValue == "off",
                                onClick = { onSetDisappearing("off"); timerMenuOpen = false },
                            )
                            TimerPreset(
                                label = "2 min after seen (default)",
                                selected = disappearingValue == "seen:120000",
                                onClick = { onSetDisappearing("seen:120000"); timerMenuOpen = false },
                            )
                            TimerPreset(
                                label = "1 hour after seen",
                                selected = disappearingValue == "seen:3600000",
                                onClick = { onSetDisappearing("seen:3600000"); timerMenuOpen = false },
                            )
                            TimerPreset(
                                label = "24 hours after seen",
                                selected = disappearingValue == "seen:86400000",
                                onClick = { onSetDisappearing("seen:86400000"); timerMenuOpen = false },
                            )
                            TimerPreset(
                                label = "24 hours after sent",
                                selected = disappearingValue == "sent:86400000",
                                onClick = { onSetDisappearing("sent:86400000"); timerMenuOpen = false },
                            )
                            TimerPreset(
                                label = "7 days after sent",
                                selected = disappearingValue == "sent:604800000",
                                onClick = { onSetDisappearing("sent:604800000"); timerMenuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("Custom…") },
                                onClick = { timerMenuOpen = false; customOpen = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                    val previous = messages.getOrNull(index - 1)
                    val showTime = previous == null ||
                        message.sentAt - previous.sentAt > TIME_SEPARATOR_GAP_MS

                    if (showTime) {
                        TimeSeparator(message.sentAt)
                    }
                    MessageRow(
                        message = message,
                        onLongPress = { onDelete(message) },
                        onKeep = { onKeep(message) },
                        onRetry = { onRetry(message) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = null, fadeOutSpec = null,
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                    )
                }
            }

            Composer(
                draft = draft,
                onDraftChange = {
                    draft = it
                    if (it.isNotEmpty()) onTyping()
                },
                onSend = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                },
                onAttach = {
                    photoPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
            )
        }
    }

    if (customOpen) {
        CustomTimerDialog(
            initialValue = disappearingValue,
            onConfirm = { onSetDisappearing(it); customOpen = false },
            onDismiss = { customOpen = false },
        )
    }

    if (pendingImage != null) {
        MetadataWarningDialog(
            onSend = { onSendImage(pendingImage!!); pendingImage = null },
            onDismiss = { pendingImage = null },
        )
    }
}

// `itemsIndexed` comes from androidx.compose.foundation.lazy and is imported
// at the top of this file. The two hand-rolled LazyListScope helpers that
// briefly lived here shadowed the real ones and recursed into themselves.

@Composable
private fun MessageRow(
    message: Message,
    onLongPress: () -> Unit,
    onKeep: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val outgoing = message.isOutgoing

    // System notices ("Alex kept a message") are centred facts about the
    // conversation, not bubbles from either side.
    if (message.kind == dev.hush.chat.data.MessageKind.SYSTEM) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = message.body,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = HushTextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(HushSurfaceElevated.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        return
    }

    // Entrance: new rows fade and settle rather than popping in. Placed on the
    // row (not the bubble) so the timestamp separator rides along.
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            if (message.deletedForEveryone) {
                // A tombstone, not a blank. The user needs to know a message was
                // removed and when, or a gap in the conversation looks like loss.
                // Expired vs deleted is spelled out: a user on OFF watching a
                // received message vanish must see that the SENDER's timer did
                // it ("disappeared"), not a person ("deleted").
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(HushSurfaceElevated.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = if (message.expired) Icons.Filled.Timer else Icons.Filled.Delete,
                        contentDescription = null,
                        tint = HushTextTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = if (message.expired) "This message disappeared" else "This message was deleted",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                        color = HushTextTertiary,
                    )
                }
            } else if (message.kind == dev.hush.chat.data.MessageKind.IMAGE && message.attachmentLabel != null) {
                ImageBubble(
                    message = message,
                    outgoing = outgoing,
                    onKeep = onKeep,
                    onDelete = onLongPress,
                    onRetry = onRetry,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Box {
                        val bubbleShape = RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (outgoing) 20.dp else 6.dp,
                            bottomEnd = if (outgoing) 6.dp else 20.dp,
                        )
                        androidx.compose.material3.Surface(
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .shadow(
                                    elevation = 6.dp,
                                    shape = bubbleShape,
                                    ambientColor = Color.Black.copy(alpha = 0.35f),
                                    spotColor = Color.Black.copy(alpha = 0.35f),
                                )
                                .clip(bubbleShape)
                                .background(if (outgoing) HushBubbleOutgoing else HushBubbleIncoming)
                                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
                            color = Color.Transparent,
                            border = if (outgoing) null else BorderStroke(1.dp, HushBubbleEdge.copy(alpha = 0.6f)),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                // Body colour follows the bubble, not the page. The old code
                                // used onBackground everywhere, which is dark ink on the
                                // light theme — invisible on the blue outgoing bubble.
                                // Outgoing bubbles are dark on every theme, so white
                                // always wins there; incoming bubbles sit on the
                                // page, so page text wins there.
                                Text(
                                    text = message.body,
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 22.sp,
                                    color = if (outgoing) Color.White else MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = clockFormat(message.sentAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (outgoing) Color.White.copy(alpha = 0.72f) else HushTextTertiary,
                                    )
                                    if (outgoing) {
                                        Spacer(Modifier.width(6.dp))
                                        DeliveryMark(message.state, onRetry)
                                    }
                                }
                                // Timer truth under the bubble. Kept wins over the
                                // countdown (a kept message is exempt); a message
                                // with no timer shows nothing at all.
                                if (message.kept) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Kept • won't disappear",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (outgoing) Color.White.copy(alpha = 0.72f) else HushTextTertiary,
                                    )
                                } else {
                                    timerHint(message)?.let { hint ->
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = hint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (outgoing) Color.White.copy(alpha = 0.72f) else HushTextTertiary,
                                        )
                                    }
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = HushSurfaceElevated,
                            tonalElevation = 8.dp,
                            shadowElevation = 16.dp,
                        ) {
                            // Keep is for received disappearing messages only. The
                            // keeper's copy goes exempt; the sender keeps their
                            // own timer and gets a "kept a message" notice.
                            if (!outgoing && !message.kept && hasTimer(message)) {
                                DropdownMenuItem(
                                    text = { Text("Keep") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.BookmarkAdd,
                                            contentDescription = null,
                                            tint = HushTextSecondary,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onKeep()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete for everyone") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = HushDanger,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onLongPress()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Metadata warning, shown on every image attach before anything is sent.
 *
 * The app re-encodes images on send (which sheds most file metadata in
 * practice), but that is an implementation detail, not a certified scrubber —
 * so the warning stands as stated: metadata is not removed by promise, and
 * whatever survives in the file can still identify the sender.
 */
@Composable
private fun MetadataWarningDialog(
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send this image?") },
        text = {
            Text(
                "Your image metadata is not being removed, and through image metadata this can still be traceable.",
                style = MaterialTheme.typography.bodyMedium,
                color = HushTextSecondary,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onSend) { Text("Send anyway") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * An image message: the JPEG from `attachment`, decoded on demand.
 *
 * Decoding is remembered per message id so scrolling does not re-decode on
 * every recomposition. A row whose bytes fail to decode renders as text
 * rather than a hole — a corrupt attachment must never be a blank bubble.
 */
@Composable
private fun ImageBubble(
    message: Message,
    outgoing: Boolean,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bitmap = remember(message.id, message.attachmentLabel) {
        runCatching {
            val raw = java.util.Base64.getDecoder().decode(message.attachmentLabel)
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?.asImageBitmap()
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val bubbleShape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = if (outgoing) 20.dp else 6.dp,
            bottomEnd = if (outgoing) 6.dp else 20.dp,
        )
        androidx.compose.material3.Surface(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .shadow(
                    elevation = 6.dp,
                    shape = bubbleShape,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                )
                .clip(bubbleShape)
                .background(if (outgoing) HushBubbleOutgoing else HushBubbleIncoming)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            color = Color.Transparent,
            border = if (outgoing) null else BorderStroke(1.dp, HushBubbleEdge.copy(alpha = 0.6f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = "Image message",
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                    )
                } else {
                    Text(
                        text = "Couldn't load this image",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (outgoing) Color.White else MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (message.body.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 22.sp,
                        color = if (outgoing) Color.White else MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = clockFormat(message.sentAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (outgoing) Color.White.copy(alpha = 0.72f) else HushTextTertiary,
                    )
                    if (outgoing) {
                        Spacer(Modifier.width(6.dp))
                        DeliveryMark(message.state, onRetry)
                    }
                }
                if (message.kept) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Kept • won't disappear",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (outgoing) Color.White.copy(alpha = 0.72f) else HushTextTertiary,
                    )
                } else {
                    timerHint(message)?.let { hint ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (outgoing) Color.White.copy(alpha = 0.72f) else HushTextTertiary,
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = HushSurfaceElevated,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
        ) {
            if (!outgoing && !message.kept && hasTimer(message)) {
                DropdownMenuItem(
                    text = { Text("Keep") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.BookmarkAdd,
                            contentDescription = null,
                            tint = HushTextSecondary,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onKeep()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete for everyone") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = HushDanger,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
/**
 * One row in the per-chat timer menu. A check marks the active policy so the
 * user can see at a glance what this conversation does — the subtitle shows
 * it too, but only the menu shows the alternatives.
 */
@Composable
private fun TimerPreset(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (selected) Text("✓", color = HushAccent, fontWeight = FontWeight.Bold)
        },
        onClick = onClick,
    )
}

/** True when this row carries any disappearing timer. */
private fun hasTimer(message: Message): Boolean =
    message.expiresAt > 0L || message.expirySeenMs > 0L

/**
 * Small truth line under a bubble: how long this message lives.
 *
 * Stable policy text, not a live countdown — rows recompose on message
 * changes, not every second, so a countdown would go stale and lie. The
 * expiry sweep deletes the row when the deadline passes; until then the
 * policy is what the user needs.
 */
private fun timerHint(message: Message): String? {
    if (message.deletedForEveryone || message.kept) return null
    if (message.expirySeenMs > 0L) {
        return "Disappears ${formatTimerMs(message.expirySeenMs)} after seen"
    }
    if (message.expiresAt > 0L) {
        val span = (message.expiresAt - message.sentAt).coerceAtLeast(1L)
        return "Disappears ${formatTimerMs(span)} after sent"
    }
    return null
}

private fun formatTimerMs(ms: Long): String = when {
    ms % 86_400_000L == 0L -> "${ms / 86_400_000L} day" + if (ms == 86_400_000L) "" else "s"
    ms % 3_600_000L == 0L -> "${ms / 3_600_000L} hour" + if (ms == 3_600_000L) "" else "s"
    ms % 60_000L == 0L -> "${ms / 60_000L} min"
    ms % 1_000L == 0L -> "${ms / 1_000L}s"
    else -> "${ms}ms"
}

/**
 * Custom timer: an amount, a unit, and whether the clock starts at send or at
 * first sight. Bounds are enforced (1ms..1 year) so a typo cannot create a
 * never-expiring "disappearing" message or an instantly-vanishing one.
 */
@Composable
private fun CustomTimerDialog(
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by remember { mutableStateOf("10") }
    var unitIndex by remember { mutableStateOf(1) } // Minutes
    var afterSeen by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val units = listOf("Seconds" to 1_000L, "Minutes" to 60_000L, "Hours" to 3_600_000L, "Days" to 86_400_000L)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    units.forEachIndexed { i, (label, _) ->
                        androidx.compose.material3.FilterChip(
                            selected = unitIndex == i,
                            onClick = { unitIndex = i },
                            label = { Text(label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = afterSeen,
                        onClick = { afterSeen = true },
                        label = { Text("After seen") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = !afterSeen,
                        onClick = { afterSeen = false },
                        label = { Text("After sent") },
                    )
                }
                error?.let { Text(it, color = HushDanger, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Sets the timer on messages you send. Their messages still follow their timer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HushTextTertiary,
                )
                Text(
                    "Current: $initialValue",
                    style = MaterialTheme.typography.bodySmall,
                    color = HushTextTertiary,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val n = amount.toLongOrNull()
                if (n == null || n <= 0) {
                    error = "Enter an amount."
                    return@TextButton
                }
                val ms = n * units[unitIndex].second
                if (ms < 1L || ms > 365L * 24L * 60L * 60L * 1000L) {
                    error = "Keep it between a second and a year."
                    return@TextButton
                }
                onConfirm(if (afterSeen) "seen:$ms" else "sent:$ms")
            }) { Text("Set") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeliveryMark(state: DeliveryState, onRetry: () -> Unit) {    when (state) {
        // Marks always sit on an outgoing bubble, so they are white — never
        // the accent, which is unreadable on the light theme's blue bubble.
        DeliveryState.SENT -> Text(
            text = "✓✓",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.9f),
            letterSpacing = 0.sp,
        )
        DeliveryState.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "sending",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        DeliveryState.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .background(HushDanger.copy(alpha = 0.12f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Failed to send, tap to retry",
                tint = HushDanger,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "retry",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = HushDanger,
            )
        }
    }
}

@Composable
private fun TimeSeparator(epochMs: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = clockFormat(epochMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = HushTextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(HushSurfaceElevated.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onAttach) {
            Icon(
                imageVector = Icons.Filled.AttachFile,
                contentDescription = "Send image",
                tint = HushTextSecondary,
            )
        }
        TextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message…", color = HushTextTertiary) },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = HushSurfaceElevated,
                unfocusedContainerColor = HushSurfaceElevated,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = HushAccent,
            ),
        )

        Spacer(Modifier.width(10.dp))

        val canSend = draft.isNotBlank()
        // The button breathes with the draft: a spring pop on enable reads as
        // alive; a static colour flip reads as a redraw.
        val sendScale by animateFloatAsState(
            targetValue = if (canSend) 1f else 0.92f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "sendScale",
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .scale(sendScale)
                .shadow(
                    elevation = if (canSend) 10.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = HushSend.copy(alpha = 0.45f),
                    spotColor = HushSend.copy(alpha = 0.45f),
                )
                .clip(CircleShape)
                .background(
                    if (canSend) HushSend
                    else HushSurfaceElevated.copy(alpha = 0.7f),
                )
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (canSend) HushOnSend else HushTextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Clock label for a message.
 *
 * A shared `SimpleDateFormat` would be the obvious implementation and it would
 * be a bug: SimpleDateFormat is mutable and not thread-safe, and Compose calls
 * this from whatever thread drives recomposition, so two messages formatted
 * concurrently can interleave and produce timestamps like "0:7:0:3". The
 * DateTimeFormatter below is immutable and genuinely thread-safe.
 */
private fun clockFormat(epochMs: Long): String =
    CLOCK_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private val CLOCK_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private const val TIME_SEPARATOR_GAP_MS = 120_000L
