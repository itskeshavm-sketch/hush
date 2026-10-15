package dev.hush.chat.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Typography.
 *
 * Tightened letter spacing on the large sizes and loosened on small caps: this
 * is the difference between a list that looks designed and one that looks like
 * a stock Android theme with the defaults left alone.
 */
private val HushTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * The app theme.
 *
 * @param themeKey one of [HushThemeKeys]; unknown values fall back to the
 *   default rather than crashing an install that restored a stale pref.
 *
 * The Material scheme is derived from the palette on every composition, and
 * the palette itself is provided down the tree — so every `Hush*` colour read
 * in every screen resolves against the selected theme with no call-site
 * changes. There is deliberately no system-dark-mode branch anymore: the user
 * picked a theme, and the theme wins.
 */
@Composable
fun HushTheme(
    themeKey: String = HushThemeKeys.DEFAULT,
    content: @Composable () -> Unit,
) {
    val palette = remember(themeKey) { hushPaletteFor(themeKey) }

    val scheme = remember(palette) {
        if (palette.isLight) {
            lightColorScheme(
                primary = palette.accent,
                onPrimary = palette.sendContent,
                primaryContainer = palette.elevated,
                onPrimaryContainer = palette.textPrimary,
                secondary = palette.textSecondary,
                onSecondary = Color.White,
                background = palette.background,
                onBackground = palette.textPrimary,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.elevated,
                onSurfaceVariant = palette.textSecondary,
                outline = palette.edge,
                outlineVariant = palette.edge,
                error = palette.danger,
                onError = Color.White,
                scrim = Color(0x99000000),
            )
        } else {
            darkColorScheme(
                primary = palette.accent,
                onPrimary = palette.sendContent,
                primaryContainer = palette.accentMuted,
                onPrimaryContainer = palette.textPrimary,
                secondary = palette.textSecondary,
                onSecondary = palette.background,
                background = palette.background,
                onBackground = palette.textPrimary,
                surface = palette.surface,
                onSurface = palette.textPrimary,
                surfaceVariant = palette.elevated,
                onSurfaceVariant = palette.textSecondary,
                outline = palette.high,
                outlineVariant = palette.high,
                error = palette.danger,
                onError = Color.White,
                scrim = Color(0xCC000000),
            )
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge: the status bar is drawn transparent and the UI
            // insets itself. On any device this looks intentional; with a solid
            // status bar it looks like a 2016 app.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = palette.isLight
        }
    }

    CompositionLocalProvider(LocalHushPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = HushTypography,
            content = content,
        )
    }
}
