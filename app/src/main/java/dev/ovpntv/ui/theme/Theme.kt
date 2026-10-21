package dev.ovpntv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Design tokens modelled on the Mullvad VPN Android design system
 * (lib/ui/theme in mullvadvpn-app): a dark-blue surface, blue primary,
 * green "positive" and red "error" accents, off-white text, small 4dp corners
 * and a stronger-than-default focus state layer, which suits D-pad navigation.
 */
object Palette {
    val Blue = Color(0xFF294D73)
    val DarkBlue = Color(0xFF192E45)
    val DarkerBlue10 = Color(0xFF101823)
    val DarkerBlue50 = Color(0xFF192638)
    val Red = Color(0xFFE34039)
    val Green = Color(0xFF44AD4D)
    val Yellow = Color(0xFFFFD524)
    val OffWhite = Color(0xFFF8F7F1)
    val Muted = Color(0xFFA3ABB5)
    val BlueOnDarkBlue10 = Color(0xFF1B314A)
    val BlueOnDarkBlue20 = Color(0xFF1C344E)
    val BlueOnDarkBlue40 = Color(0xFF1F3A57)
    val BlueOnDarkBlue60 = Color(0xFF234161)
    val DisabledGreen = Color(0xFF2A6148)
    val DisabledRed = Color(0xFF6A3540)
    val DisabledBlue = Color(0xFF1F3A57)
}

private val colors = darkColorScheme(
    primary = Palette.Blue,
    onPrimary = Palette.OffWhite,
    primaryContainer = Palette.Blue,
    onPrimaryContainer = Palette.OffWhite,
    secondary = Palette.DarkerBlue50,
    onSecondary = Palette.OffWhite,
    secondaryContainer = Palette.DarkerBlue50,
    onSecondaryContainer = Palette.Muted,
    tertiary = Palette.DarkerBlue10,
    onTertiary = Palette.OffWhite,
    tertiaryContainer = Palette.DarkerBlue10,
    onTertiaryContainer = Palette.Muted,
    background = Palette.DarkBlue,
    onBackground = Palette.OffWhite,
    surface = Palette.DarkBlue,
    onSurface = Palette.OffWhite,
    surfaceVariant = Palette.DarkerBlue50,
    onSurfaceVariant = Palette.Muted,
    surfaceBright = Palette.Blue,
    surfaceContainerHighest = Palette.Blue,
    surfaceContainerHigh = Palette.BlueOnDarkBlue60,
    surfaceContainer = Palette.BlueOnDarkBlue40,
    surfaceContainerLow = Palette.BlueOnDarkBlue20,
    surfaceContainerLowest = Palette.BlueOnDarkBlue10,
    inverseSurface = Palette.OffWhite,
    error = Palette.Red,
    onError = Palette.OffWhite,
    errorContainer = Palette.Red,
    onErrorContainer = Palette.OffWhite,
    outline = Palette.Blue,
    outlineVariant = Palette.DarkBlue,
    scrim = Color.Black,
)

val ColorScheme.positive: Color get() = Palette.Green
val ColorScheme.warning: Color get() = Palette.Yellow

private val typography = with(Typography()) {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val shapes = Shapes(
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(11.dp),
)

object Dimens {
    val buttonHeight = 44.dp
    val cellHeight = 56.dp
    val cellHeightTwoRows = 72.dp
    val smallPadding = 8.dp
    val mediumPadding = 16.dp
    val largePadding = 32.dp
    /** TV overscan-safe margins. */
    val screenHorizontal = 48.dp
    val screenVertical = 27.dp
    val chipShape = RoundedCornerShape(8.dp)
    val focusBorder = 2.dp
    const val FOCUS_OVERLAY_ALPHA = 0.24f
}

/** androidx.tv components (the navigation drawer) read their own theme, so mirror the palette there. */
private val tvColors = androidx.tv.material3.darkColorScheme(
    primary = Palette.Blue,
    onPrimary = Palette.OffWhite,
    secondaryContainer = Palette.BlueOnDarkBlue40,
    onSecondaryContainer = Palette.OffWhite,
    surface = Palette.DarkBlue,
    onSurface = Palette.OffWhite,
    surfaceVariant = Palette.BlueOnDarkBlue40,
    onSurfaceVariant = Palette.OffWhite,
    inverseSurface = Palette.OffWhite,
    inverseOnSurface = Palette.DarkBlue,
    border = Palette.OffWhite,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    androidx.tv.material3.MaterialTheme(colorScheme = tvColors) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
