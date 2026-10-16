package dev.hush.chat.ui.modern

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hush.chat.ui.theme.HushSurfaceElevated

/**
 * Modern chrome: solid surfaces, hairline dividers, zero blur APIs.
 *
 * The previous glass attempt used RenderEffect blur on the bar itself, which
 * blurs its own content (text included) instead of what's behind it. That is
 * why the top bar smeared gray and cards turned unreadable. This file does
 * the opposite: fully opaque backgrounds drawn from the active palette, plus
 * a 1dp divider for definition. Works identically on API 26 and API 34, costs
 * nothing per frame, and never touches readability.
 */
object ModernChrome {
    @Composable
    fun dividerColor() = HushSurfaceElevated

    @Composable
    fun TopDivider() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor()),
        )
    }

    @Composable
    fun BottomDivider() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor()),
        )
    }
}

/** Solid top-bar background: palette background, no translucency. */
@Composable
fun modernTopBarBackground(): Modifier = Modifier.background(MaterialTheme.colorScheme.background)
