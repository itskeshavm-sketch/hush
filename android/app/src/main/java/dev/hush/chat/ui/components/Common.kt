package dev.hush.chat.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hush.chat.ui.theme.HushDanger
import dev.hush.chat.ui.theme.HushOnline
import dev.hush.chat.ui.theme.HushSurfaceElevated
import dev.hush.chat.ui.theme.HushTextSecondary
import dev.hush.chat.ui.theme.HushVerified
import dev.hush.chat.ui.theme.avatarTintFor

/**
 * Initials avatar with a deterministic tint and a presence dot.
 *
 * There are no uploaded avatars in HUSH. That is not a missing feature: an
 * image upload is a permanent, linkable identifier for a person who chose to
 * stay anonymous, and it would undo the entire premise. Initials plus a stable
 * colour give the list enough visual texture to scan without reintroducing that.
 */
@Composable
fun InitialsAvatar(
    initials: String,
    seed: Int,
    size: Dp = 48.dp,
    online: Boolean? = null,
    verified: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarTintFor(seed).copy(alpha = 0.22f))
                .border(1.dp, avatarTintFor(seed).copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                color = avatarTintFor(seed),
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
            )
        }

        if (verified) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(size * 0.34f),
                shape = CircleShape,
                color = HushSurfaceElevated,
            ) {
                Icon(
                    imageVector = Icons.Filled.Verified,
                    contentDescription = "Verified",
                    tint = HushVerified,
                    modifier = Modifier
                        .padding(1.dp)
                        .size(size * 0.3f),
                )
            }
        }

        if (online != null) {
            PresenceDot(
                online = online,
                size = size,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The presence dot needs `BoxScope` to align itself, so it is declared as an
 * extension on BoxScope rather than a standalone composable. As a plain
 * `@Composable fun` it has no access to `Modifier.align` and will not compile.
 */
@Composable
private fun BoxScope.PresenceDot(online: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size * 0.28f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .clip(CircleShape)
            .background(if (online) HushOnline else HushTextSecondary.copy(alpha = 0.4f))
    )
}

/**
 * Connection banner.
 *
 * Shown whenever the relay is not fully connected. It is deliberately loud
 * during reconnection — a user who cannot tell "my message was sent" from
 * "my message is sitting in a queue" will assume the worse and resend.
 */
@Composable
fun ConnectionBanner(
    state: dev.hush.chat.net.RelayState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (message, tint, pulse) = when (state) {
        is dev.hush.chat.net.RelayState.Connecting ->
            Triple("Connecting to relay…", HushTextSecondary, true)

        is dev.hush.chat.net.RelayState.Reconnecting ->
            Triple("Reconnecting — ${state.cause.take(48)}", HushDanger, true)

        is dev.hush.chat.net.RelayState.Failed ->
            Triple(state.reason, HushDanger, false)

        else -> return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = tint, animate = pulse)
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (state is dev.hush.chat.net.RelayState.Failed) {
            Text(
                text = "RETRY",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickableNoRipple(onRetry),
            )
        }
    }
}

@Composable
fun PulsingDot(color: Color, animate: Boolean, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (animate) 0.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/** A three-dot typing indicator. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier, color: Color = HushTextSecondary) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val offset by transition.animateFloat(
                initialValue = 0f,
                targetValue = -3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.5f + offset / 6f))
            )
        }
    }
}

/** Section header used across list screens. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = HushTextSecondary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Empty-state block. Explains *what to do*, not just that there is nothing. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = HushTextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}
