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
 * The Home illustration: the kit's arch over two hills (arch_panel, a crop of
 * artwork/arch-bird-16x9.svg) with the Plain bird drawn separately so it can move.
 *
 * The bird reflects real service state only, and the screen always says the same thing in text:
 *  - Connected: it flies in from the top-right corner and perches on the arch, facing the status text.
 *  - Scheduled pause: it dozes on the arch (sleepy pose). Reconnecting: it waits there (curious).
 *  - Disconnected: it turns and flies off. If the connection failed, a concerned bird stands on the hill.
 * One glide per state change, no loops or bouncing (kit motion rules). With animations
 * turned off in Android's settings it just appears or disappears.
 */

// Geometry in arch_panel viewport units (600 x 480), taken from the kit artwork.
private const val ART_W = 600f
private const val ART_H = 480f
/** Top centre of the arch, where the bird's feet go. */
private const val PERCH_X = 275f
private const val PERCH_Y = 65f
/** The artwork draws the bird at 0.55 of its 240 x 200 master. */
private const val BIRD_W = 240f * 0.55f
private const val BIRD_H = 200f * 0.55f
/** Feet centre within the bird master, as fractions (x is mirrored: the bird faces left). */
private const val FEET_X_MIRRORED = (240f - 133f) / 240f
private const val FEET_Y = 197f / 200f
/** Spot on the sky hill, left of the arch, for the concerned bird. */
private const val HILL_X = 70f
private const val HILL_Y = 282f

private enum class Phase { Away, Arriving, Perched, Leaving }

@Composable
fun PerchScene(state: TunnelState, modifier: Modifier = Modifier) {
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
    val initiallyPerched = wantPerched == true
    var phase by remember { mutableStateOf(if (initiallyPerched) Phase.Perched else Phase.Away) }
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
        val s = w / ART_W // dp per artwork unit
        val artTop = maxHeight - s * ART_H
        val birdW = s * BIRD_W
        val birdH = s * BIRD_H
        // Top-left of the bird when perched (mirrored, feet on the arch).
        val perchX = s * PERCH_X - birdW * FEET_X_MIRRORED
        val perchY = artTop + s * PERCH_Y - birdH * FEET_Y

        Image(
            painterResource(R.drawable.arch_panel),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        )

        val error = (state as? TunnelState.Disconnected)?.error != null
        AnimatedVisibility(
            visible = error && phase == Phase.Away,
            enter = fadeIn(tween(Plain.SHEET_MS)),
            exit = fadeOut(tween(Plain.STANDARD_MS)),
            modifier = Modifier.offset(x = s * HILL_X - birdW * FEET_X_MIRRORED, y = artTop + s * HILL_Y - birdH * FEET_Y),
        ) {
            Bird(R.drawable.bird_concerned, birdW, birdH, mirrored = true)
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
                                val cx = (w * 0.3f).toPx()
                                val cy = (birdH * 0.25f).toPx()
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
