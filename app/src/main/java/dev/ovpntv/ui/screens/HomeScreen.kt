package dev.ovpntv.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ovpntv.R
import dev.ovpntv.data.Profile
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.data.ReconnectSchedule
import dev.ovpntv.data.Settings
import dev.ovpntv.data.SplitMode
import dev.ovpntv.schedule.ReconnectScheduler
import dev.ovpntv.ui.components.ButtonStyle
import dev.ovpntv.ui.components.ErrorBanner
import dev.ovpntv.ui.components.GroundHeight
import dev.ovpntv.ui.components.FeatureChip
import dev.ovpntv.ui.components.PerchScene
import dev.ovpntv.ui.components.PlainCard
import dev.ovpntv.ui.components.TvButton
import dev.ovpntv.ui.components.UpdateBanner
import dev.ovpntv.ui.components.focusRing
import dev.ovpntv.ui.components.plainIcon
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.theme.Plain
import dev.ovpntv.update.UpdateState
import dev.ovpntv.update.Updater
import dev.ovpntv.vpn.TunnelState
import dev.ovpntv.vpn.VpnState
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Connection screen, after the kit's plain tunnel spec: wordmark, status symbol + heading,
 * the selected profile as a location card, live details, one apricot primary action. The
 * kit artwork is the ground: hills along the bottom, the arch and bird on the right (see PerchScene).
 */
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

    Box(Modifier.fillMaxSize()) {
        // The kit's hills run along the bottom as the ground; the arch and bird stand on the right.
        PerchScene(state, archCenter = 0.8f)

        Row(Modifier.fillMaxSize().padding(start = dev.ovpntv.ui.ContentStart, top = Dimens.screenVertical, end = Dimens.screenHorizontal)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = Dimens.largePadding, bottom = GroundHeight + Dimens.mediumPadding),
            ) {
                Image(
                    painterResource(R.drawable.plain_tunnel_wordmark),
                    contentDescription = "plain tunnel",
                    modifier = Modifier.height(36.dp),
                )
                Spacer(Modifier.height(28.dp))
                StatusHero(state, blocking, split.isActive)
                (state as? TunnelState.Disconnected)?.error?.let {
                    Spacer(Modifier.height(Dimens.mediumPadding))
                    ErrorBanner(it)
                }
                Spacer(Modifier.weight(1f))
                ProfileCard(state, selected, profiles.isNotEmpty(), onOpenProfiles)
                Spacer(Modifier.height(Dimens.mediumPadding))

                // One primary button whose label changes with the state, so focus stays on it
                // instead of jumping to the drawer when Connect turns into Cancel and back.
                val failed = (state as? TunnelState.Disconnected)?.error != null
                val (primaryText, primaryOnClick) = when (state) {
                    is TunnelState.Disconnected -> (if (failed) "Try again" else "Connect") to { if (selected == null) onOpenProfiles() else onConnect(selected.id) }
                    is TunnelState.Connecting -> "Cancel" to onDisconnect
                    is TunnelState.ScheduledPause -> "Stay disconnected" to onDisconnect
                    TunnelState.Disconnecting -> "Disconnecting…" to onDisconnect
                    else -> "Disconnect" to onDisconnect
                }
                // Side by side so Unblock can't be pushed off screen by the error banner.
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumPadding)) {
                    TvButton(
                        text = primaryText,
                        onClick = primaryOnClick,
                        style = ButtonStyle.Primary,
                        icon = plainIcon(R.drawable.pi_power),
                        modifier = Modifier.weight(1f).focusRequester(primaryFocus),
                    )
                    if (blocking && state is TunnelState.Disconnected) {
                        // This button disappears once clicked, so hand focus to Connect first.
                        TvButton(
                            "Unblock apps",
                            { runCatching { primaryFocus.requestFocus() }; onDisconnect() },
                            Modifier.weight(1f),
                            icon = plainIcon(R.drawable.pi_unlock),
                        )
                    } else if (!schedule.enabled) {
                        TvButton("Schedule", onOpenSchedule, Modifier.weight(1f), icon = plainIcon(R.drawable.pi_clock))
                    }
                }
            }

            // Details and the update banner sit in the sky above the arch, clear of the bird.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .padding(bottom = 210.dp),
            ) {
                AppUpdateBanner(Modifier)
                Spacer(Modifier.weight(1f))
                ConnectionDetails(state)
                if (schedule.enabled || split.isActive) {
                    // Stacked rather than side by side: the column is too narrow for both on one line.
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding)) {
                        if (schedule.enabled) ScheduleChip(schedule)
                        if (split.isActive) {
                            val count = split.apps.size
                            FeatureChip(
                                icon = plainIcon(R.drawable.pi_tv),
                                text = when (split.mode) {
                                    SplitMode.OnlySelected -> "$count app${if (count == 1) "" else "s"} on VPN"
                                    else -> "$count app${if (count == 1) "" else "s"} bypass"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Status hero under the wordmark, after the bible's desktop connection screen: a small
 * "VPN connection" label with the lock symbol, the state as a big Fredoka heading, then one
 * line of plain explanation. Symbol + words, so the state never depends on colour.
 */
@Composable
private fun StatusHero(state: TunnelState, blocking: Boolean, splitActive: Boolean) {
    val connected = state is TunnelState.Connected
    val busy = state is TunnelState.Connecting || state is TunnelState.Disconnecting
    val circle by animateColorAsState(if (connected) Plain.Sage else Plain.Raised, tween(Plain.SHEET_MS), label = "statusCircle")
    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                Box(Modifier.size(44.dp).background(circle, CircleShape))
                if (busy) CircularProgressIndicator(color = Plain.Apricot, strokeWidth = 2.5.dp, trackColor = Plain.Raised, modifier = Modifier.size(44.dp))
                Icon(
                    plainIcon(if (connected || busy || state is TunnelState.ScheduledPause) R.drawable.pi_lock else R.drawable.pi_unlock),
                    contentDescription = null,
                    tint = if (connected) Plain.Pine else Plain.Cream,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("VPN connection", style = MaterialTheme.typography.titleMedium, color = Plain.Muted)
        }
        Spacer(Modifier.height(10.dp))
        val heading = statusText(state)
        // Long states ("Couldn't connect.", "Disconnecting…") step down a size so they stay on one line.
        val size = if (heading.length > 12) 40.sp else 48.sp
        Text(
            text = heading,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = size, lineHeight = 54.sp),
            color = Plain.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = statusDetail(state, blocking, splitActive),
            style = MaterialTheme.typography.bodyLarge,
            color = Plain.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The selected profile, shown like the kit's location card. Opens Profiles. */
@Composable
private fun ProfileCard(state: TunnelState, selected: Profile?, hasProfiles: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val name = when (state) {
        is TunnelState.Connected -> state.profileName
        is TunnelState.Connecting -> state.profileName
        is TunnelState.ScheduledPause -> state.profileName
        else -> selected?.name ?: "Choose a profile"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .focusRing(focused, 16.dp)
            .clip(Dimens.cardShape)
            .background(if (focused) Plain.Raised else Plain.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.mediumPadding, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(Plain.Canvas, CircleShape)) {
            Icon(plainIcon(R.drawable.pi_location), contentDescription = null, tint = Plain.Apricot, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Plain.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                selected?.let { "${it.serverLabel} · via OpenVPN ${it.protocol?.uppercase().orEmpty()}".trim() } ?: if (hasProfiles) "Pick the one to connect with" else "No profile imported yet",
                style = MaterialTheme.typography.bodyMedium,
                color = Plain.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(plainIcon(R.drawable.pi_chevron_right), contentDescription = "Change profile", tint = Plain.Text, modifier = Modifier.size(24.dp))
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
        is UpdateState.Downloading -> Triple("Downloading update ${s.info.versionName}… ${s.percent}%", null, null)
        is UpdateState.Installing -> Triple("Installing update ${s.info.versionName}…", "The app restarts when it's done. The VPN reconnects by itself.", null)
        is UpdateState.NeedsPermission -> Triple(
            "Allow updates",
            "Find plain tunnel in the list, switch it on, then press Back. The update continues by itself.",
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
        icon = plainIcon(R.drawable.pi_clock),
        text = next?.let {
            // Weekday + time in the TV's locale, e.g. "Wed 4:00 AM", so the chip stays one line.
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEjmm")
            "Reconnect ${java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(Date(it))}"
        }
            ?: "Reconnect · no days selected",
    )
}

/** Live values from the service, as label/value pairs in a two-column card. Nothing is shown that the service didn't report. */
@Composable
private fun ConnectionDetails(state: TunnelState) {
    val ticking = state is TunnelState.Connected || state is TunnelState.ScheduledPause
    val now = rememberNow(1000, enabled = ticking)
    val traffic by VpnState.traffic.collectAsState()
    val blocking by VpnState.blocking.collectAsState()
    val blockedRow = if (blocking) "VPN apps" to "Blocked until the VPN is back" else null
    val rows = when (state) {
        is TunnelState.Connected -> listOf(
            "Tunnel IP" to (state.localIp ?: "Unavailable"),
            "Server IP" to (state.serverIp ?: "Unavailable"),
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
    if (rows.isEmpty()) return
    PlainCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        rows.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = Plain.Muted)
                        Text(value, style = MaterialTheme.typography.bodyLarge, color = Plain.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun statusText(state: TunnelState) = when (state) {
    is TunnelState.Connected -> "Connected."
    is TunnelState.Connecting -> "Connecting…"
    is TunnelState.Disconnected -> if (state.error != null) "Couldn't connect." else "Not connected."
    TunnelState.Disconnecting -> "Disconnecting…"
    is TunnelState.ScheduledPause -> "Paused."
}

private fun statusDetail(state: TunnelState, blocking: Boolean, splitActive: Boolean) = when (state) {
    is TunnelState.Connected -> if (splitActive) "Your chosen apps use the VPN tunnel." else "Your TV's apps use the VPN tunnel."
    is TunnelState.Connecting -> if (blocking) "VPN apps stay offline until it's up." else "Setting up the tunnel."
    is TunnelState.Disconnected -> when {
        blocking -> "VPN apps are blocked until you reconnect."
        state.error != null -> "Check the message above, then try again."
        else -> "Apps use your normal internet connection."
    }
    TunnelState.Disconnecting -> "Closing the tunnel."
    is TunnelState.ScheduledPause -> "The scheduled reconnect. Back shortly."
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
