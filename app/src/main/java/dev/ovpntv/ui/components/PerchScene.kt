package dev.ovpntv.ui.components

import android.provider.Settings as AndroidSettings
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ovpntv.R
import dev.ovpntv.ui.theme.Plain
import dev.ovpntv.vpn.TunnelState

/*
 * The Home backdrop, from the kit artwork (artwork/arch-bird-16x9.svg): its two hills as a
 * low ground band along the bottom of the screen, the arch standing on it at [archCenter],
 * and the Plain bird drawn separately so it can move.
 *
 * The bird reflects real service state only, and the screen always says the same thing in text:
 *  - Connected: it flies in from the top-right corner and perches on the arch, facing the status text.
 *  - Scheduled pause: it dozes on the arch (sleepy pose). Reconnecting: it waits there (curious).
 *  - Disconnected: it turns and flies off. If the connection failed, a concerned bird stands by the arch.
 * One glide per state change, no loops or bouncing (kit motion rules). With animations
 * turned off in Android's settings it just appears or disappears.
 */

/** Height of the hills band; content above it should leave at least this much room. */
val GroundHeight = 60.dp
private val ArchWidth = 72.dp
/** The arch master is 250 x 405 artwork units; the bird master 240 x 200, drawn at 0.55 in the artwork. */
private const val ARCH_ASPECT = 405f / 250f
private const val BIRD_SCALE = 0.55f * 1.35f // a touch bigger than the artwork so it reads on a TV
/** Feet centre within the bird master, as fractions (x is mirrored: the bird faces left). */
private const val FEET_X_MIRRORED = (240f - 133f) / 240f
private const val FEET_Y = 197f / 200f

/** Feet centre x within the unmirrored bird master. */
private const val FEET_X = 133f / 240f
/** Where the concerned bird stands, as a fraction of the width: near the top of the green hill. */
private const val CONCERNED_AT = 0.3f

/**
 * Top of the sage hill at [f] (fraction of the band width), as a fraction of the band height.
 * The hill's first segment is the kit curve M0 780 Q350 530 800 800 in the 1600-wide artwork,
 * and the band shows y 640..900 of it.
 */
private fun sageHillTop(f: Float): Float {
    val x = (1600f * f).coerceAtMost(800f)
    // x(t) = 700t + 100t² on this segment; solve for t, then evaluate y(t).
    val t = (-700f + kotlin.math.sqrt(700f * 700f + 400f * x)) / 200f
    val u = 1f - t
    val y = 780f * u * u + 2f * 530f * u * t + 800f * t * t
    return (y - 640f) / 260f
}

private enum class Phase { Away, Arriving, Perched, Leaving }

/** [archCenter] is the arch's horizontal centre as a fraction of the width. */
@Composable
fun PerchScene(state: TunnelState, modifier: Modifier = Modifier, archCenter: Float = 0.78f) {
    val context = LocalContext.current
    val reducedMotion = remember {
        AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val wantPerched: Boolean? = when (state) {
        is TunnelState.Connected, is TunnelState.ScheduledPause -> true
        is TunnelState.Disconnected -> false
        else -> null // Connecting / Disconnecting: stay wherever it is.
    }
    // Opening the app on an already-running tunnel shows the bird already perched; it only flies on changes.
    var phase by remember { mutableStateOf(if (wantPerched == true) Phase.Perched else Phase.Away) }
    val flight = remember { Animatable(0f) }

    LaunchedEffect(wantPerched) {
        when {
            wantPerched == true && phase != Phase.Perched -> {
                phase = Phase.Arriving
                if (!reducedMotion) {
                    flight.snapTo(0f)
                    flight.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
                }
                phase = Phase.Perched
            }
            wantPerched == false && phase != Phase.Away -> {
                phase = Phase.Leaving
                if (!reducedMotion) {
                    flight.snapTo(0f)
                    flight.animateTo(1f, tween(900, easing = FastOutLinearInEasing))
                }
                phase = Phase.Away
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val archH = ArchWidth * ARCH_ASPECT
        val archLeft = w * archCenter - ArchWidth / 2
        val archTop = h - archH
        // One artwork unit in dp, from the arch's 250-unit width.
        val unit = ArchWidth / 250f
        val birdW = unit * 240f * BIRD_SCALE
        val birdH = unit * 200f * BIRD_SCALE
        // Top-left of the bird when perched (mirrored, feet on top of the arch).
        val perchX = w * archCenter - birdW * FEET_X_MIRRORED
        val perchY = archTop - birdH * FEET_Y

        Image(
            painterResource(R.drawable.hills_band),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxWidth().height(GroundHeight).align(Alignment.BottomCenter),
        )
        Image(
            painterResource(R.drawable.arch),
            contentDescription = null,
            modifier = Modifier.offset(x = archLeft, y = archTop).size(ArchWidth, archH),
        )

        val error = (state as? TunnelState.Disconnected)?.error != null
        AnimatedVisibility(
            visible = error && phase == Phase.Away,
            enter = fadeIn(tween(Plain.SHEET_MS)),
            exit = fadeOut(tween(Plain.STANDARD_MS)),
            // On the green hill (the blue one reads as water), looking across at the empty arch.
            modifier = Modifier.offset(
                x = w * CONCERNED_AT - birdW * FEET_X,
                y = h - GroundHeight + GroundHeight * sageHillTop(CONCERNED_AT) - birdH * FEET_Y,
            ),
        ) {
            Bird(R.drawable.bird_concerned, birdW, birdH, mirrored = false)
        }

        if (phase != Phase.Away) {
            val density = LocalDensity.current
            val pose = when {
                phase == Phase.Arriving || phase == Phase.Leaving -> R.drawable.bird_waving // wing up while gliding
                state is TunnelState.ScheduledPause -> R.drawable.bird_sleepy
                state is TunnelState.Connected -> R.drawable.bird_perched
                else -> R.drawable.bird_curious // reconnecting or disconnecting: watching
            }
            val leaving = phase == Phase.Leaving
            Box(
                Modifier
                    .offset(x = perchX, y = perchY)
                    .graphicsLayer {
                        val t = if (phase == Phase.Arriving || leaving) flight.value else 1f
                        with(density) {
                            if (leaving) {
                                // Turns to face right and climbs out of the top-right corner, nose up.
                                translationX = lerp(0f, (w - perchX + birdW).toPx(), t)
                                translationY = lerp(0f, -(perchY + birdH * 1.5f).toPx(), t * t)
                                rotationZ = lerp(-7f, -20f, t)
                            } else if (phase == Phase.Arriving) {
                                // Quadratic curve from beyond the top-right corner, dipping, then levelling onto the arch.
                                val sx = (w - perchX + birdW * 0.3f).toPx()
                                val sy = -(perchY + birdH * 1.4f).toPx()
                                val cx = (birdW * 2.5f).toPx()
                                val cy = (birdH * 0.3f).toPx()
                                val u = 1f - t
                                translationX = u * u * sx + 2 * u * t * cx
                                translationY = u * u * sy + 2 * u * t * cy
                                rotationZ = lerp(-18f, 7f, t)
                                val sc = lerp(0.8f, 1f, t)
                                scaleX = sc
                                scaleY = sc
                            } else {
                                rotationZ = 7f
                            }
                        }
                    },
            ) {
                Crossfade(pose, animationSpec = tween(Plain.SHEET_MS), label = "pose") { p ->
                    Bird(p, birdW, birdH, mirrored = !leaving)
                }
            }
        }
    }
}

@Composable
private fun Bird(@DrawableRes pose: Int, width: Dp, height: Dp, mirrored: Boolean) {
    Image(
        painterResource(pose),
        contentDescription = null, // decorative: the status text says the same thing
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 0.6f)
                scaleX = if (mirrored) -1f else 1f
            },
    )
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
