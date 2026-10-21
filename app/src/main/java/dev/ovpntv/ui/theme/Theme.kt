package dev.ovpntv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ovpntv.R

/*
 * "plainVPN" theme, from the Plain AI Style Kit (tokens/design-tokens.json, dark theme):
 * near-black blue canvas, subtly raised blue-black panels, white text, one blue accent
 * with a dark label, Inter throughout. TV focus is a white inner + light-blue outer ring
 * (see Components.kt), so it never relies on the blue fill alone.
 */
object Plain {
    // Named palette from the board (illustration and accents).
    val Blue = Color(0xFF3B82F6)
    val Violet = Color(0xFF8B5CF6)
    val Sunset = Color(0xFFF97316)
    /** The logo's period blue. */
    val LogoBlue = Color(0xFF1677FF)

    // Dark theme semantic tokens.
    val Canvas = Color(0xFF0B1017)
    val Surface = Color(0xFF111B25)
    val Raised = Color(0xFF192431)
    val Text = Color(0xFFF8FAFC)
    val Muted = Color(0xFFB0BDCE)
    val Border = Color(0xFF293746)
    val Primary = Blue
    val OnPrimary = Color(0xFF071426)
    val Selected = Color(0xFF142D48)
    val Focus = Color(0xFF7DB5FF)
    // Not in the kit's tokens; chosen for 4.5:1+ on the dark surfaces.
    val Success = Color(0xFF4ADE80)
    val Danger = Color(0xFFF87171)
    val OnDanger = Color(0xFF1A0707)

    /** Motion tokens (ms): kit short/normal, plus a slower one for sheets and pose changes. */
    const val QUICK_MS = 150
    const val STANDARD_MS = 220
    const val SHEET_MS = 260
}

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Inter variable font, pinned to the four weights the kit uses. */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

private val colors = darkColorScheme(
    primary = Plain.Primary,
    onPrimary = Plain.OnPrimary,
    primaryContainer = Plain.Primary,
    onPrimaryContainer = Plain.OnPrimary,
    secondary = Plain.Surface,
    onSecondary = Plain.Text,
    secondaryContainer = Plain.Selected,
    onSecondaryContainer = Plain.Text,
    tertiary = Plain.Raised,
    onTertiary = Plain.Text,
    tertiaryContainer = Plain.Surface,
    onTertiaryContainer = Plain.Muted,
    background = Plain.Canvas,
    onBackground = Plain.Text,
    surface = Plain.Canvas,
    onSurface = Plain.Text,
    surfaceVariant = Plain.Surface,
    onSurfaceVariant = Plain.Muted,
    surfaceBright = Plain.Raised,
    surfaceContainerHighest = Plain.Raised,
    surfaceContainerHigh = Plain.Raised,
    surfaceContainer = Plain.Surface,
    surfaceContainerLow = Plain.Surface,
    surfaceContainerLowest = Plain.Canvas,
    inverseSurface = Plain.Text,
    inverseOnSurface = Plain.Canvas,
    error = Plain.Danger,
    onError = Plain.OnDanger,
    errorContainer = Plain.Danger,
    onErrorContainer = Plain.OnDanger,
    outline = Plain.Border,
    outlineVariant = Plain.Raised,
    scrim = Color.Black,
)

val ColorScheme.positive: Color get() = Plain.Success
val ColorScheme.warning: Color get() = Plain.Sunset

private fun style(size: Int, line: Int, weight: Int, tracking: Float = 0f) =
    TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

/** Kit scale (display 48/56 … caption 12/16), nudged up a step where it's read from the sofa. */
private val typography = Typography(
    displaySmall = style(44, 52, 700, -0.5f),
    headlineLarge = style(36, 44, 700, -0.4f),
    headlineMedium = style(30, 38, 600, -0.3f),
    headlineSmall = style(28, 36, 600, -0.3f),
    titleLarge = style(22, 28, 600),
    titleMedium = style(18, 24, 600),
    titleSmall = style(16, 22, 600),
    bodyLarge = style(18, 26, 500),
    bodyMedium = style(16, 24, 400),
    bodySmall = style(14, 20, 400),
    labelLarge = style(16, 22, 500),
    labelMedium = style(14, 20, 500),
    labelSmall = style(12, 16, 500),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

object Dimens {
    val buttonHeight = 48.dp
    val cellHeight = 56.dp
    val cellHeightTwoRows = 72.dp
    val smallPadding = 8.dp
    val mediumPadding = 16.dp
    val cardPadding = 24.dp
    val largePadding = 32.dp
    /** TV safe margin. The vertical one is trimmed so Home fits a 540dp screen. */
    val screenHorizontal = 48.dp
    val screenVertical = 32.dp
    val controlShape = RoundedCornerShape(12.dp)
    val cardShape = RoundedCornerShape(16.dp)
    val chipShape = controlShape
    /** TV focus: 3dp white inner ring, 3dp gap, 3dp light-blue outer ring. */
    val focusRing = 3.dp
    val focusGap = 3.dp
    /** How far the outer focus ring reaches past a control; clipping parents pad by this. */
    val focusOutset = 6.dp
}

/** androidx.tv components read their own theme, so mirror the palette there. */
private val tvColors = androidx.tv.material3.darkColorScheme(
    primary = Plain.Primary,
    onPrimary = Plain.OnPrimary,
    secondaryContainer = Plain.Selected,
    onSecondaryContainer = Plain.Text,
    surface = Plain.Canvas,
    onSurface = Plain.Text,
    surfaceVariant = Plain.Surface,
    onSurfaceVariant = Plain.Text,
    inverseSurface = Plain.Text,
    inverseOnSurface = Plain.Canvas,
    border = Plain.Focus,
)

private val tvTypography = androidx.tv.material3.Typography(
    titleMedium = style(20, 26, 600),
    labelLarge = style(16, 22, 500),
    bodyLarge = style(16, 22, 500),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    androidx.tv.material3.MaterialTheme(colorScheme = tvColors, typography = tvTypography) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
