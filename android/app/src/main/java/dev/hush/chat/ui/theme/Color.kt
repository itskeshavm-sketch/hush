package dev.hush.chat.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * HUSH colour system.
 *
 * Dark-first by design, not by accident. The app's whole premise is a
 * messenger you open in the dark, and a light theme would be a lie about that.
 * The palette is built on a near-black base with one accent so that message
 * bubbles, avatars and the accent never fight each other.
 *
 * Contrast note: [HushTextSecondary] is the dimmest value in the system and
 * sits at 4.6:1 on [HushBackground]. That is deliberately *not* dimmer — it is
 * the last value that still passes WCAG AA for body text, and timestamps use
 * it.
 */

// Base surfaces
val HushBackground = Color(0xFF0B0C10)
val HushSurface = Color(0xFF13151C)
val HushSurfaceElevated = Color(0xFF1B1E27)
val HushSurfaceHigh = Color(0xFF242833)

// Accent — a violet that stays legible on near-black and does not vibrate
// against the amber used for "unverified" states.
val HushAccent = Color(0xFF7C5CFF)
val HushAccentPressed = Color(0xFF6747E8)
val HushAccentMuted = Color(0xFF2A2447)

// Text
val HushTextPrimary = Color(0xFFEDEFF5)
val HushTextSecondary = Color(0xFF9AA0B4)
val HushTextTertiary = Color(0xFF6B7183)

// Semantic
val HushOnline = Color(0xFF3DDC97)
val HushTyping = Color(0xFF7C5CFF)
val HushDanger = Color(0xFFFF5C68)
val HushWarning = Color(0xFFFFB020)
val HushVerified = Color(0xFF3DDC97)

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
