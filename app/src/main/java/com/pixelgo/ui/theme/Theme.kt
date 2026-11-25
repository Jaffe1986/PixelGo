package com.pixelgo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PixelGo's design tokens (docs/DESIGN.md). Graphite surfaces so the art is the brightest thing
 * on screen. Yellow means "this one" (the current tool, frame, swatch or selection) and marks the
 * one main action on a surface. Nothing else is yellow.
 *
 * There is a single (dark) theme, so tokens are plain constants: usable in composables and in
 * Canvas drawing code alike.
 */
object Pg {
    // Surfaces, darkest to lightest.
    val Background = Color(0xFF141414)
    /** Bars, the tool dock, rails. */
    val Surface1 = Color(0xFF1C1C1C)
    /** Sheets, cards, dialogs. */
    val Surface2 = Color(0xFF242424)
    /** Inputs, secondary buttons, menus. */
    val Surface3 = Color(0xFF2E2E2E)
    /** Around the art: a true neutral, so colors read correctly. */
    val Canvas = Color(0xFF2A2A2A)
    /** Dividers and 1dp strokes. */
    val Outline = Color(0xFF383838)

    // Text and icons.
    val Text1 = Color(0xFFF0F0F0)
    val Text2 = Color(0xFFA6A6A6)
    /** Disabled only. */
    val Text3 = Color(0xFF666666)

    // Accent and meaning.
    val Accent = Color(0xFFFFD166)
    val OnAccent = Color(0xFF1A1A1A)
    /** Accent at 16%: selection fills, toggles that are on. */
    val AccentWeak = Color(0x29FFD166)
    /** The playhead and recording. */
    val Live = Color(0xFFFF4D4D)
    /** Destructive actions. */
    val Danger = Color(0xFFFF7A73)
    val Success = Color(0xFF5BD68A)
    val Snackbar = Color(0xFF333333)

    /** Sound track colors, in track order (repeating). */
    val Tracks = listOf(
        Color(0xFF5B9BF0), Color(0xFFF0954A), Color(0xFFA78BFA),
        Color(0xFF3CC8B4), Color(0xFFF07AA8), Color(0xFFA8D65B),
    )

    /** Numbers that change while you watch (time, sizes, counts): tabular so they don't jitter. */
    val Data = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

    /** Timeline marks (ruler, frame numbers): the smallest text in the app. */
    val Mark = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")

    // Sizes.
    val TopBarHeight = 56.dp
    val TopBarHeightCompact = 48.dp
    val BarHeight = 48.dp
    val ActionBarHeight = 52.dp
    val DockHeight = 64.dp
    val ColorRowHeight = 52.dp
    val Swatch = 32.dp
    val CurrentColor = 40.dp
}

private val Scheme = darkColorScheme(
    primary = Pg.Accent,
    onPrimary = Pg.OnAccent,
    primaryContainer = Pg.Accent,
    onPrimaryContainer = Pg.OnAccent,
    inversePrimary = Color(0xFF8A6200),
    // Selected chips: the accent at 16% (as a solid, so it works on any surface).
    secondary = Color(0xFFD9D9D9),
    onSecondary = Pg.OnAccent,
    secondaryContainer = Color(0xFF463E2C),
    onSecondaryContainer = Pg.Accent,
    tertiary = Pg.Success,
    onTertiary = Pg.OnAccent,
    tertiaryContainer = Color(0xFF1F3A2A),
    onTertiaryContainer = Pg.Success,
    background = Pg.Background,
    onBackground = Pg.Text1,
    surface = Pg.Surface1,
    onSurface = Pg.Text1,
    surfaceVariant = Pg.Surface3,
    onSurfaceVariant = Pg.Text2,
    // No yellow tint on raised surfaces.
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Pg.Surface1,
    error = Pg.Danger,
    onError = Pg.OnAccent,
    errorContainer = Color(0xFF3A1E1C),
    onErrorContainer = Pg.Danger,
    outline = Color(0xFF5A5A5A),
    outlineVariant = Pg.Outline,
    scrim = Color.Black,
    surfaceBright = Pg.Outline,
    surfaceDim = Pg.Background,
    // Mapped so Material's defaults land on PixelGo's surfaces: sheets (Low) and dialogs (High)
    // on surface-2, menus (Container) on surface-3.
    surfaceContainerLowest = Color(0xFF101010),
    surfaceContainerLow = Pg.Surface2,
    surfaceContainer = Pg.Surface3,
    surfaceContainerHigh = Pg.Surface2,
    surfaceContainerHighest = Pg.Surface3,
)

private val Type = Typography().let { d ->
    Typography(
        // Dialog titles use headlineSmall.
        headlineSmall = d.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
        // Title: screens, sheets.
        titleLarge = d.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
        // Heading: sections inside sheets.
        titleMedium = d.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        titleSmall = d.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = d.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        // Body: text, menus.
        bodyMedium = d.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = d.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = d.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        // Label: anything tappable in bars.
        labelMedium = d.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = d.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

/** Radii: 2 art containers (drawn directly), 6 controls, 12 cards and menus, 16 sheets and dialogs. */
private val Shape = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun PixelGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = Shape, content = content)
}
