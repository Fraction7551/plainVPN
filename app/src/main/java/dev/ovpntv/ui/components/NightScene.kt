package dev.ovpntv.ui.components

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.ovpntv.R
import dev.ovpntv.vpn.TunnelState

/*
 * The Home backdrop for the plainVPN design: a night ridge-and-lake band along the bottom
 * (pv_ground, see design-tools/plainvpn_assets.py) and a moon that tracks the real tunnel state.
 * [moonAt] sits over the right-hand pine stand, so the reflection falls under its straight shoreline (x > 0.83).
 *
 *  - Connected: the moon rises from behind the ridge and lays a faint shimmer on the lake.
 *  - Scheduled pause: it hangs low and dim, half behind the ridge.
 *  - Disconnected: it sets behind the ridge.
 *  - Connecting / Disconnecting: it stays where it is (the status text and spinner say what's happening).
 * One movement per state change, no loops. With animations off in Android's settings it just
 * appears or disappears. Decorative only; the status heading always says the same thing.
 */

/** Height of the ground band; content above it should leave at least this much room. */
val GroundHeight = 100.dp
private val MoonRadius = 15.dp
private val HaloRadius = 70.dp
/** Below the straight shoreline under the right-hand pines (y 180 of 220 in pv_ground). */
private const val REFLECTION_TOP = 180f / 220f

@Composable
fun NightScene(state: TunnelState, modifier: Modifier = Modifier, moonAt: Float = 0.87f) {
    val context = LocalContext.current
    val reducedMotion = remember {
        AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val target: Float? = when (state) {
        is TunnelState.Connected -> 1f
        is TunnelState.ScheduledPause -> 0.45f
        is TunnelState.Disconnected -> 0f
        else -> null
    }
    // Opening the app on a running tunnel shows the moon already up; it only moves on changes.
    val rise = remember { Animatable(target ?: 0f) }
    LaunchedEffect(target) {
        if (target == null || target == rise.value) return@LaunchedEffect
        if (reducedMotion) rise.snapTo(target)
        else if (target > rise.value) rise.animateTo(target, tween(1600, easing = FastOutSlowInEasing))
        else rise.animateTo(target, tween(900, easing = FastOutLinearInEasing))
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val groundTop = maxHeight - GroundHeight
        // Moon and halo sit behind the ridges, so setting = sliding down behind them.
        Canvas(Modifier.fillMaxSize()) {
            val p = rise.value
            if (p <= 0f) return@Canvas
            val x = size.width * moonAt
            val hidden = groundTop.toPx() + GroundHeight.toPx() * 0.55f + MoonRadius.toPx()
            val risen = groundTop.toPx() - 64.dp.toPx()
            val y = hidden + (risen - hidden) * p
            val glow = p * 0.22f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF7DB5FF).copy(alpha = glow), Color.Transparent), Offset(x, y), HaloRadius.toPx()),
                HaloRadius.toPx(), Offset(x, y),
            )
            drawCircle(Color(0xFFEFF4FF).copy(alpha = 0.35f + 0.65f * p), MoonRadius.toPx(), Offset(x, y))
        }
        Image(
            painterResource(R.drawable.pv_ground),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxWidth().height(GroundHeight).align(Alignment.BottomCenter),
        )
        // The moon's reflection: short strokes on the lake, narrowing and fading downward.
        Canvas(Modifier.fillMaxSize()) {
            val p = rise.value
            if (p <= 0.05f) return@Canvas
            val x = size.width * moonAt
            val lakeTop = groundTop.toPx() + GroundHeight.toPx() * REFLECTION_TOP
            val rows = 5
            for (i in 0 until rows) {
                val y = lakeTop + (i + 0.6f) * (size.height - lakeTop) / (rows + 0.5f)
                val half = (24 - i * 4).dp.toPx() * (0.6f + 0.4f * p)
                drawLine(
                    Color(0xFFBFD6FF).copy(alpha = p * (0.42f - i * 0.07f)),
                    Offset(x - half, y), Offset(x + half, y),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}
