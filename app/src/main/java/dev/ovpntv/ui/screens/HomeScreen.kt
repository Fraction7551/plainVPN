package dev.ovpntv.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ovpntv.R
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.data.ReconnectSchedule
import dev.ovpntv.data.Settings
import dev.ovpntv.data.SplitMode
import dev.ovpntv.schedule.ReconnectScheduler
import dev.ovpntv.ui.components.ErrorBanner
import dev.ovpntv.ui.components.FeatureChip
import dev.ovpntv.ui.components.NegativeButton
import dev.ovpntv.ui.components.TvButton
import dev.ovpntv.ui.components.UpdateBanner
import dev.ovpntv.update.UpdateState
import dev.ovpntv.update.Updater
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.theme.positive
import dev.ovpntv.vpn.TunnelState
import dev.ovpntv.vpn.VpnState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    onConnect: (String?) -> Unit,
    onDisconnect: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
    val state by VpnState.state.collectAsState()
    val selectedId by Settings.selectedProfileId.collectAsState()
    val profiles by ProfileRepository.profiles.collectAsState()
    val schedule by Settings.schedule.collectAsState()
    val split by Settings.splitTunnel.collectAsState()
    val selected = profiles.firstOrNull { it.id == selectedId }

    val blocking by VpnState.blocking.collectAsState()

    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }

    Row(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            (state as? TunnelState.Disconnected)?.error?.let {
                ErrorBanner(it)
                Spacer(Modifier.height(Dimens.mediumPadding))
            }
            Spacer(Modifier.weight(1f))

            Text(
                text = statusText(state),
                color = statusColor(state),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (val s = state) {
                    is TunnelState.Connected -> s.profileName
                    is TunnelState.Connecting -> s.profileName
                    is TunnelState.ScheduledPause -> s.profileName
                    else -> selected?.name ?: "No profile"
                },
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            selected?.let {
                Text(
                    "${it.serverLabel} · ${it.protocol?.uppercase()}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Dimens.mediumPadding))
            ConnectionDetails(state)

            if (schedule.enabled) {
                Spacer(Modifier.height(Dimens.mediumPadding))
                ScheduleChip(schedule)
            }
            if (split.isActive) {
                Spacer(Modifier.height(Dimens.smallPadding))
                val count = split.apps.size
                FeatureChip(
                    icon = Icons.Rounded.AltRoute,
                    text = when (split.mode) {
                        SplitMode.OnlySelected -> "Split tunneling · VPN for $count app${if (count == 1) "" else "s"} only"
                        else -> "Split tunneling · $count app${if (count == 1) "" else "s"} bypass the VPN"
                    },
                )
            }

            Spacer(Modifier.height(Dimens.largePadding))
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumPadding), modifier = Modifier.width(420.dp)) {
                TvButton(text = "Switch profile", onClick = onOpenProfiles, icon = Icons.AutoMirrored.Rounded.List)
                // One primary button whose label changes with the state, so focus stays on it
                // instead of jumping to the drawer when Connect turns into Cancel and back.
                val (primaryText, primaryOnClick) = when (state) {
                    is TunnelState.Disconnected -> "Connect" to { if (selected == null) onOpenProfiles() else onConnect(selected.id) }
                    is TunnelState.Connecting -> "Cancel" to onDisconnect
                    is TunnelState.ScheduledPause -> "Stay disconnected" to onDisconnect
                    else -> "Disconnect" to onDisconnect
                }
                // Side by side so Unblock can't be pushed off screen by the error banner.
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumPadding)) {
                    TvButton(
                        text = primaryText,
                        onClick = primaryOnClick,
                        modifier = Modifier.weight(1f).focusRequester(primaryFocus),
                        containerColor = if (state is TunnelState.Disconnected) MaterialTheme.colorScheme.positive else MaterialTheme.colorScheme.error,
                    )
                    if (blocking && state is TunnelState.Disconnected) {
                        // This button disappears once clicked, so hand focus to Connect first.
                        NegativeButton("Unblock apps", { runCatching { primaryFocus.requestFocus() }; onDisconnect() }, Modifier.weight(1f))
                    }
                }
                if (!schedule.enabled) {
                    TvButton(
                        text = "Set up scheduled reconnect",
                        onClick = onOpenSchedule,
                        icon = Icons.Rounded.Schedule,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    )
                }
            }
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxHeight()) {
            StatusShield(state)
            // Over the shield's empty top area, so it never pushes the left column's buttons off screen.
            AppUpdateBanner(Modifier.align(Alignment.TopCenter).padding(start = Dimens.largePadding))
        }
    }
}

/** Shown only while an update is available or being installed. */
@Composable
private fun AppUpdateBanner(modifier: Modifier) {
    val context = LocalContext.current
    val update by Updater.state.collectAsState()
    val install = { Updater.install(context) }
    val (title, detail, action) = when (val s = update) {
        is UpdateState.Available -> Triple("Update available: ${s.info.versionName}", s.info.notes.ifBlank { null }, "Install update")
        is UpdateState.Downloading -> Triple("Downloading update ${s.info.versionName}... ${s.percent}%", null, null)
        is UpdateState.Installing -> Triple("Installing update ${s.info.versionName}...", "The app restarts when it's done. The VPN reconnects by itself.", null)
        is UpdateState.NeedsPermission -> Triple(
            "Allow updates",
            "Find OpenVPN TV in the list, switch it on, then press Back. The update continues by itself.",
            "Install update",
        )
        is UpdateState.Failed -> if (s.info != null) Triple("Update ${s.info.versionName} didn't install", s.message, "Try again") else return
        else -> return
    }
    UpdateBanner(title, detail, action, install, modifier)
}

/**
 * Current time, refreshed every [periodMillis] while [enabled]. Only the composable that
 * calls this recomposes on each tick, so keep callers small.
 */
@Composable
private fun rememberNow(periodMillis: Long, enabled: Boolean = true): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis, enabled) {
        while (enabled) {
            now = System.currentTimeMillis()
            delay(periodMillis)
        }
    }
    return now
}

@Composable
private fun ScheduleChip(schedule: ReconnectSchedule) {
    val now = rememberNow(60_000)
    val next = remember(schedule, now / 60_000) { ReconnectScheduler.nextTrigger(schedule, now) }
    FeatureChip(
        icon = Icons.Rounded.Schedule,
        text = next?.let { "Auto reconnect · next ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" }
            ?: "Auto reconnect · no days selected",
    )
}

@Composable
private fun ConnectionDetails(state: TunnelState) {
    val ticking = state is TunnelState.Connected || state is TunnelState.ScheduledPause
    val now = rememberNow(1000, enabled = ticking)
    val traffic by VpnState.traffic.collectAsState()
    val blocking by VpnState.blocking.collectAsState()
    val blockedRow = if (blocking) "VPN apps" to "Blocked until the VPN is back" else null
    val rows = when (state) {
        is TunnelState.Connected -> listOfNotNull(
            state.localIp?.let { "Tunnel IP" to it },
            state.serverIp?.let { "Server IP" to it },
            "Connected for" to formatDuration(now - state.sinceMillis),
            "Traffic" to "↓ ${formatBytes(traffic.bytesIn)}   ↑ ${formatBytes(traffic.bytesOut)}",
        )
        is TunnelState.Connecting -> listOfNotNull("Status" to state.stage, blockedRow)
        is TunnelState.ScheduledPause -> listOfNotNull(
            "Reconnecting in" to formatDuration((state.resumeAtMillis - now).coerceAtLeast(0)),
            blockedRow,
        )
        is TunnelState.Disconnected -> listOfNotNull(blockedRow?.let { it.first to "Blocked until you reconnect" })
        else -> emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Stand-in for Mullvad's map: the app shield inside a ring tinted by the tunnel state. */
@Composable
private fun StatusShield(state: TunnelState) {
    val ring by animateColorAsState(statusColor(state), label = "ring")
    val busy = state is TunnelState.Connecting || state is TunnelState.Disconnecting || state is TunnelState.ScheduledPause
    Box(contentAlignment = Alignment.Center) {
        if (busy) PulsingHalo(ring) else Box(Modifier.size(300.dp).alpha(0.18f).background(ring, CircleShape))
        Box(
            Modifier
                .size(220.dp)
                .border(4.dp, ring, CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape),
        )
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(200.dp))
    }
}

/** The infinite transition only exists while busy, and its value is read in the draw layer. */
@Composable
private fun PulsingHalo(color: Color) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale = pulse.animateFloat(1f, 1.15f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
    Box(
        Modifier
            .size(300.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = 0.18f }
            .background(color, CircleShape),
    )
}

private fun statusText(state: TunnelState) = when (state) {
    is TunnelState.Connected -> "CONNECTED"
    is TunnelState.Connecting -> "CONNECTING..."
    is TunnelState.Disconnected -> "DISCONNECTED"
    TunnelState.Disconnecting -> "DISCONNECTING..."
    is TunnelState.ScheduledPause -> "SCHEDULED RECONNECT"
}

@Composable
private fun statusColor(state: TunnelState): Color = when (state) {
    is TunnelState.Connected -> MaterialTheme.colorScheme.positive
    is TunnelState.Disconnected -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun formatDuration(millis: Long): String {
    val s = millis / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
