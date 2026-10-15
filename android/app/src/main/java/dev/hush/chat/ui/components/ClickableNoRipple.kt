package dev.hush.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * Clickable without the ripple.
 *
 * Used on small, dense, already-highlighted affordances (a "RETRY" label, a
 * timestamp) where Material's ripple reads as a rendering bug rather than
 * feedback. Ordinary list rows keep their ripple.
 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}
