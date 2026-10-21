package dev.ovpntv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ovpntv.R

/*
 * "plain tunnel" theme, from the Plain Option 6 kit v1.3.0 (tokens/plain.tokens.json,
 * dark theme): pine-teal canvas, cream text, apricot as the single primary accent with
 * pine labels, sage/sky only in illustration. Fredoka 600 for titles, Nunito Sans for
 * everything else. TV focus is a cream inner + apricot outer ring (see Components.kt).
 */
object Plain {
    // Printed palette.
    val Pine = Color(0xFF173D3B)
    val Cream = Color(0xFFF6F0E3)
    val Apricot = Color(0xFFEEA36C)
    val Sage = Color(0xFF94AB89)
    val Sky = Color(0xFFA9C5CC)

    // Dark theme semantic tokens.
    val Canvas = Pine
    val Surface = Color(0xFF214B48)
    val Raised = Color(0xFF2B5954)
    val Text = Cream
    val Muted = Color(0xFFC0D0C6)
    val Border = Color(0xFF7F9F94)
    val Primary = Apricot
    val OnPrimary = Pine
    val Focus = Apricot
    val Success = Color(0xFFA6D6AF)
    val Danger = Color(0xFFFFB1A8)
    val OnDanger = Pine
    val Selected = Color(0xFF315F58)

    /** Motion tokens (ms). */
    const val QUICK_MS = 120
    const val STANDARD_MS = 180
    const val SHEET_MS = 240
}

val Fredoka = FontFamily(Font(R.font.fredoka_semibold, FontWeight.SemiBold))
val NunitoSans = FontFamily(
    Font(R.font.nunito_sans_regular, FontWeight.Normal),
    Font(R.font.nunito_sans_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_sans_bold, FontWeight.Bold),
)

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
    inverseSurface = Plain.Cream,
    inverseOnSurface = Plain.Pine,
    error = Plain.Danger,
    onError = Plain.OnDanger,
    errorContainer = Plain.Danger,
    onErrorContainer = Plain.OnDanger,
    outline = Plain.Border,
    outlineVariant = Plain.Raised,
    scrim = Color.Black,
)

val ColorScheme.positive: Color get() = Plain.Success
val ColorScheme.warning: Color get() = Plain.Apricot

/** Kit TV type scale (display 36/44, title 28/36), with body/label one step smaller so dense lists fit 540dp. */
private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

object Dimens {
    val buttonHeight = 48.dp
    val cellHeight = 56.dp
    val cellHeightTwoRows = 72.dp
    val smallPadding = 8.dp
    val mediumPadding = 16.dp
    val cardPadding = 24.dp
    val largePadding = 32.dp
    /** TV safe margin (kit: 48dp). The vertical one is trimmed so Home fits a 540dp screen. */
    val screenHorizontal = 48.dp
    val screenVertical = 32.dp
    val controlShape = RoundedCornerShape(12.dp)
    val cardShape = RoundedCornerShape(16.dp)
    val chipShape = controlShape
    /** Kit TV focus: 3dp cream inner ring, 3dp gap, 3dp apricot outer ring. */
    val focusRing = 3.dp
    val focusGap = 3.dp
    /** How far the outer focus ring reaches past a control; clipping parents pad by this. */
    val focusOutset = 6.dp
}

/** androidx.tv components (the navigation drawer) read their own theme, so mirror the palette there. */
private val tvColors = androidx.tv.material3.darkColorScheme(
    primary = Plain.Primary,
    onPrimary = Plain.OnPrimary,
    secondaryContainer = Plain.Selected,
    onSecondaryContainer = Plain.Text,
    surface = Plain.Canvas,
    onSurface = Plain.Text,
    surfaceVariant = Plain.Surface,
    onSurfaceVariant = Plain.Text,
    inverseSurface = Plain.Cream,
    inverseOnSurface = Plain.Pine,
    border = Plain.Focus,
)

private val tvTypography = androidx.tv.material3.Typography(
    titleMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    labelLarge = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = NunitoSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    androidx.tv.material3.MaterialTheme(colorScheme = tvColors, typography = tvTypography) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
