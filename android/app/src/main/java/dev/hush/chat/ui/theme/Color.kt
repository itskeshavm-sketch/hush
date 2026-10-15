package dev.hush.chat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * One theme's full token set.
 *
 * Every screen reads colour through here (via the `Hush*` getters below), so
 * adding a theme is one instance, not a hundred call-site edits. The five
 * instances are the approved directions from `dist/previews/style*.html`,
 * built under the vendored frontend-design skill
 * (`docs/skills/frontend-design/SKILL.md`): each grounded in the subject — a
 * secret messenger — with exactly one bold element and motion limited to
 * presses plus a single load moment.
 */
data class HushPalette(
    val key: String,
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val high: Color,
    val edge: Color,
    val accent: Color,
    val accentPressed: Color,
    val accentMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val bubbleOutgoing: Color,
    val bubbleIncoming: Color,
    val bubbleEdge: Color,
    val danger: Color,
    val warning: Color,
    val online: Color,
    val verified: Color,
    val typing: Color,
    val send: Color,
    val sendContent: Color,
    val isLight: Boolean,
)

/** Stable keys. Persisted in prefs; never rename one. */
object HushThemeKeys {
    const val LAMP = "lamp"
    const val REDACTED = "redacted"
    const val SPEAKEASY = "speakeasy"
    const val CLEAN = "clean"
    const val NEON = "neon"

    const val DEFAULT = LAMP
}

private val LampPalette = HushPalette(
    key = HushThemeKeys.LAMP,
    background = Color(0xFF0A1512),
    surface = Color(0xFF0E1D18),
    elevated = Color(0xFF16241F),
    high = Color(0xFF2A3D33),
    edge = Color(0xFF2E4036),
    accent = Color(0xFFFFB454),
    accentPressed = Color(0xFFE09A3E),
    accentMuted = Color(0xFF3A2E1A),
    textPrimary = Color(0xFFF0EFE6),
    textSecondary = Color(0xFF9DB3A8),
    textTertiary = Color(0xFF5F7268),
    bubbleOutgoing = Color(0xFF24382F),
    bubbleIncoming = Color(0xFF16241F),
    bubbleEdge = Color(0xFF2E4036),
    danger = Color(0xFFD6542E),
    warning = Color(0xFFFFB020),
    online = Color(0xFFFFB454),
    verified = Color(0xFF3DDC97),
    typing = Color(0xFFFFB454),
    send = Color(0xFFFFB454),
    sendContent = Color(0xFF241500),
    isLight = false,
)

private val RedactedPalette = HushPalette(
    key = HushThemeKeys.REDACTED,
    background = Color(0xFF1E2229),
    surface = Color(0xFF282D36),
    elevated = Color(0xFF313845),
    high = Color(0xFF3A414D),
    edge = Color(0xFF3A414D),
    accent = Color(0xFFC83A2E),
    accentPressed = Color(0xFFA92E24),
    accentMuted = Color(0xFF40201C),
    textPrimary = Color(0xFFECE7D9),
    textSecondary = Color(0xFF8A8F98),
    textTertiary = Color(0xFF5B606B),
    bubbleOutgoing = Color(0xFF2B313C),
    bubbleIncoming = Color(0xFF232830),
    bubbleEdge = Color(0xFF3A414D),
    danger = Color(0xFFC83A2E),
    warning = Color(0xFFFFB020),
    online = Color(0xFFC83A2E),
    verified = Color(0xFF3DDC97),
    typing = Color(0xFFC83A2E),
    send = Color(0xFFC83A2E),
    sendContent = Color(0xFFFFFFFF),
    isLight = false,
)

private val SpeakeasyPalette = HushPalette(
    key = HushThemeKeys.SPEAKEASY,
    background = Color(0xFF171209),
    surface = Color(0xFF1F190D),
    elevated = Color(0xFF2C2413),
    high = Color(0xFF4A3F22),
    edge = Color(0xFF4A3F22),
    accent = Color(0xFFC9A227),
    accentPressed = Color(0xFFA8871F),
    accentMuted = Color(0xFF38300F),
    textPrimary = Color(0xFFE9DFC9),
    textSecondary = Color(0xFF9A8C68),
    textTertiary = Color(0xFF6B6046),
    bubbleOutgoing = Color(0xFF2C2413),
    bubbleIncoming = Color(0xFF221B0E),
    bubbleEdge = Color(0xFF4A3F22),
    danger = Color(0xFF7A1E2B),
    warning = Color(0xFFFFB020),
    online = Color(0xFFC9A227),
    verified = Color(0xFF3DDC97),
    typing = Color(0xFFC9A227),
    send = Color(0xFF7A1E2B),
    sendContent = Color(0xFFE9DFC9),
    isLight = false,
)

private val CleanPalette = HushPalette(
    key = HushThemeKeys.CLEAN,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFEEF0F3),
    high = Color(0xFFDCDEE6),
    edge = Color(0xFFDCDEE6),
    accent = Color(0xFF002FA7),
    accentPressed = Color(0xFF002180),
    accentMuted = Color(0xFFD8DFFF),
    textPrimary = Color(0xFF101418),
    textSecondary = Color(0xFF5A5F70),
    textTertiary = Color(0xFF9AA0AE),
    bubbleOutgoing = Color(0xFF002FA7),
    bubbleIncoming = Color(0xFFEEF0F3),
    bubbleEdge = Color(0xFFDCDEE6),
    danger = Color(0xFFC8232E),
    warning = Color(0xFFB77900),
    online = Color(0xFF002FA7),
    verified = Color(0xFF1E8449),
    typing = Color(0xFF002FA7),
    send = Color(0xFF002FA7),
    sendContent = Color(0xFFFFFFFF),
    isLight = true,
)

private val NeonPalette = HushPalette(
    key = HushThemeKeys.NEON,
    background = Color(0xFF000000),
    surface = Color(0xFF0C0C0E),
    elevated = Color(0xFF1C1C1F),
    high = Color(0xFF2E2E33),
    edge = Color(0xFF2E2E33),
    accent = Color(0xFFFF4D8D),
    accentPressed = Color(0xFFD13A75),
    accentMuted = Color(0xFF3D1526),
    textPrimary = Color(0xFFF2F2F4),
    textSecondary = Color(0xFF8E8E93),
    textTertiary = Color(0xFF4A4A4E),
    bubbleOutgoing = Color(0xFF1C1C1F),
    bubbleIncoming = Color(0xFF0C0C0E),
    bubbleEdge = Color(0xFFFF4D8D),
    danger = Color(0xFFFF5C68),
    warning = Color(0xFFFFB020),
    online = Color(0xFFFF4D8D),
    verified = Color(0xFF3DDC97),
    typing = Color(0xFFFF4D8D),
    send = Color(0xFFFF4D8D),
    sendContent = Color(0xFF1A0010),
    isLight = false,
)

/** Every theme the settings picker offers, in display order. */
val HushThemeCatalog: List<HushPalette> =
    listOf(LampPalette, RedactedPalette, SpeakeasyPalette, CleanPalette, NeonPalette)

fun hushPaletteFor(key: String): HushPalette =
    HushThemeCatalog.firstOrNull { it.key == key } ?: LampPalette

val LocalHushPalette = compositionLocalOf { LampPalette }

/**
 * Theme-aware colour reads. Call sites stay exactly as they were —
 * `HushAccent`, `HushTextSecondary` — and resolve against whichever palette
 * `HushTheme` provided. All uses are inside @Composable scopes (screens,
 * components, call UI), which is what makes this indirection compile.
 */
val HushBackground: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.background
val HushSurface: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.surface
val HushSurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.elevated
val HushSurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.high
val HushEdge: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.edge
val HushAccent: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.accent
val HushAccentPressed: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.accentPressed
val HushAccentMuted: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.accentMuted
val HushTextPrimary: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.textPrimary
val HushTextSecondary: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.textSecondary
val HushTextTertiary: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.textTertiary
val HushBubbleOutgoing: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.bubbleOutgoing
val HushBubbleIncoming: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.bubbleIncoming
val HushBubbleEdge: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.bubbleEdge
val HushDanger: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.danger
val HushWarning: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.warning
val HushOnline: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.online
val HushVerified: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.verified
val HushTyping: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.typing
val HushSend: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.send
val HushOnSend: Color @Composable @ReadOnlyComposable get() = LocalHushPalette.current.sendContent

/**
 * Deterministic avatar tints.
 *
 * Every peer gets a stable colour derived from their id, so the same person is
 * the same colour on every device without any coordination — which matters
 * because HUSH has no server-side profile storage to hang a colour on.
 */
val AvatarTints: List<Color> = listOf(
    Color(0xFF7C5CFF),
    Color(0xFF2ED3B7),
    Color(0xFFFF8A4C),
    Color(0xFFE05A7A),
    Color(0xFF4C9AFF),
    Color(0xFFB07CFF),
    Color(0xFFF2C14E),
    Color(0xFF5CD1A6),
)

fun avatarTintFor(seed: Int): Color = AvatarTints[seed.mod(AvatarTints.size)]
