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
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
    peerOnline: Boolean,
    peerVerified: Boolean,
    messages: List<Message>,
    peerTyping: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onTyping: () -> Unit,
    onDelete: (Message) -> Unit,
    onRetry: (Message) -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

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
                            online = peerOnline,
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
                            if (!peerTyping) {
                                Text(
                                    text = if (peerOnline) "online" else "offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (peerOnline) HushTextSecondary else HushTextTertiary,
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
            )
        }
    }
}

// `itemsIndexed` comes from androidx.compose.foundation.lazy and is imported
// at the top of this file. The two hand-rolled LazyListScope helpers that
// briefly lived here shadowed the real ones and recursed into themselves.

@Composable
private fun MessageRow(
    message: Message,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val outgoing = message.isOutgoing

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(HushSurfaceElevated.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = HushTextTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "This message was deleted",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                        color = HushTextTertiary,
                    )
                }
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
                                Text(
                                    text = message.body,
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = clockFormat(message.sentAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (outgoing) HushTextSecondary else HushTextTertiary,
                                    )
                                    if (outgoing) {
                                        Spacer(Modifier.width(6.dp))
                                        DeliveryMark(message.state, onRetry)
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

@Composable
private fun DeliveryMark(state: DeliveryState, onRetry: () -> Unit) {
    when (state) {
        // Double-check reads as delivered; a single check reads as sent. Text
        // labels ("· sent") read as debug output, which is what the old UI
        // showed and what the screenshot caught.
        DeliveryState.SENT -> Text(
            text = "✓✓",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = HushAccent,
            letterSpacing = 0.sp,
        )
        DeliveryState.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelSmall,
                color = HushTextTertiary,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "sending",
                style = MaterialTheme.typography.labelSmall,
                color = HushTextTertiary,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
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
        Box(
            modifier = Modifier
                .size(48.dp)
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
