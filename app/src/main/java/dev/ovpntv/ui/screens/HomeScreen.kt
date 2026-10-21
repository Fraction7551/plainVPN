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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.text.style.TextAlign
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
import dev.ovpntv.ui.ContentStart
import dev.ovpntv.ui.RailWidth
import dev.ovpntv.ui.components.ButtonStyle
import dev.ovpntv.ui.components.FeatureChip
import dev.ovpntv.ui.components.PerchScene
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
import java.util.Locale

/*
 * Connection screen. One centred axis, read top to bottom in the order of the kit's
 * plain tunnel spec (and of most VPN apps): what state am I in, through which profile,
 * what can I do about it, then the numbers.
 *
 *   top bar      wordmark                                  feature chips
 *   centre       [lock] Connected.
 *                one line of plain explanation
 *                profile card
 *                primary action (+ one secondary when relevant)
 *                quiet stats strip (only values the service reported)
 *   ground       kit hills, small arch on the right, the bird (PerchScene)
 */

private val ColumnWidth = 520.dp

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
    val blocking by VpnState.blocking.collectAsState()
    val selected = profiles.firstOrNull { it.id == selectedId }

    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        PerchScene(state, archCenter = 0.9f)

        Column(Modifier.fillMaxSize().padding(start = ContentStart - RailWidth, end = Dimens.screenHorizontal, top = 28.dp)) {
            TopBar(schedule, split)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 84.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(ColumnWidth)) {
                    // Rare, so it may push the column down rather than cover it.
                    AppUpdateBanner(Modifier.padding(bottom = 20.dp))
                    Status(state, blocking, split.isActive)
                    Spacer(Modifier.height(24.dp))
                    ProfileCard(state, selected, profiles.isNotEmpty(), onOpenProfiles)
                    Spacer(Modifier.height(12.dp))
                    Actions(state, selected, blocking, schedule.enabled, primaryFocus, onConnect, onDisconnect, onOpenProfiles, onOpenSchedule)
                    Spacer(Modifier.height(20.dp))
                    StatsStrip(state)
                }
            }
        }

    }
}

@Composable
private fun TopBar(schedule: ReconnectSchedule, split: dev.ovpntv.data.SplitTunnel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Image(painterResource(R.drawable.plain_tunnel_wordmark), contentDescription = "plain tunnel", modifier = Modifier.height(34.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.smallPadding)) {
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
            if (schedule.enabled) ScheduleChip(schedule)
        }
    }
}

/** Symbol + big heading on one line, the explanation centred under it. Never colour alone. */
@Composable
private fun Status(state: TunnelState, blocking: Boolean, splitActive: Boolean) {
    val connected = state is TunnelState.Connected
    val busy = state is TunnelState.Connecting || state is TunnelState.Disconnecting
    val failed = (state as? TunnelState.Disconnected)?.error
    val circle by animateColorAsState(
        when {
            connected -> Plain.Sage
            failed != null -> Plain.Danger
            else -> Plain.Raised
        },
        tween(Plain.SHEET_MS), label = "statusCircle",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                Box(Modifier.size(56.dp).background(circle, CircleShape))
                if (busy) CircularProgressIndicator(color = Plain.Apricot, strokeWidth = 3.dp, trackColor = Plain.Raised, modifier = Modifier.size(56.dp))
                Icon(
                    plainIcon(
                        when {
                            failed != null -> R.drawable.pi_warning
                            connected || busy || state is TunnelState.ScheduledPause -> R.drawable.pi_lock
                            else -> R.drawable.pi_unlock
                        },
                    ),
                    contentDescription = null,
                    tint = if (connected || failed != null) Plain.Pine else Plain.Cream,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            val heading = statusText(state)
            Text(
                text = heading,
                // Long states ("Couldn't connect.", "Disconnecting…") step down so they stay on one line.
                style = MaterialTheme.typography.displaySmall.copy(fontSize = if (heading.length > 12) 42.sp else 48.sp, lineHeight = 56.sp),
                color = Plain.Text,
                maxLines = 1,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = failed ?: statusDetail(state, blocking, splitActive),
            style = MaterialTheme.typography.bodyLarge,
            color = if (failed != null) Plain.Danger else Plain.Muted,
            textAlign = TextAlign.Center,
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
                selected?.let { "via OpenVPN ${it.protocol?.uppercase().orEmpty()} · ${it.serverLabel}".trim() }
                    ?: if (hasProfiles) "Pick the one to connect with" else "No profile imported yet",
                style = MaterialTheme.typography.bodyMedium,
                color = Plain.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Change", style = MaterialTheme.typography.labelLarge, color = Plain.Muted)
        Icon(plainIcon(R.drawable.pi_chevron_right), contentDescription = null, tint = Plain.Text, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Actions(
    state: TunnelState,
    selected: Profile?,
    blocking: Boolean,
    scheduleEnabled: Boolean,
    primaryFocus: FocusRequester,
    onConnect: (String?) -> Unit,
    onDisconnect: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenSchedule: () -> Unit,
) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.mediumPadding)) {
        TvButton(
            text = primaryText,
            onClick = primaryOnClick,
            style = ButtonStyle.Primary,
            icon = plainIcon(R.drawable.pi_power),
            modifier = Modifier.weight(1.4f).focusRequester(primaryFocus),
        )
        if (blocking && state is TunnelState.Disconnected) {
            // This button disappears once clicked, so hand focus to Connect first.
            TvButton(
                "Unblock apps",
                { runCatching { primaryFocus.requestFocus() }; onDisconnect() },
                Modifier.weight(1f),
                icon = plainIcon(R.drawable.pi_unlock),
            )
        } else if (!scheduleEnabled && state is TunnelState.Disconnected) {
            TvButton("Schedule", onOpenSchedule, Modifier.weight(1f), icon = plainIcon(R.drawable.pi_clock))
        }
    }
}

/**
 * Live values from the service in a quiet strip under the actions: no card, thin dividers,
 * small labels. Nothing is shown that the service didn't report; the slot keeps its height
 * so the column doesn't jump when the state changes.
 */
@Composable
private fun StatsStrip(state: TunnelState) {
    val ticking = state is TunnelState.Connected || state is TunnelState.ScheduledPause
    val now = rememberNow(1000, enabled = ticking)
    val traffic by VpnState.traffic.collectAsState()
    val blocking by VpnState.blocking.collectAsState()
    val blockedRow = if (blocking) "VPN apps" to "Blocked" else null
    val cells = when (state) {
        is TunnelState.Connected -> listOf(
            "Connected for" to formatDuration(now - state.sinceMillis),
            // No server IP here: the profile card right above already shows the server.
            "Tunnel IP" to (state.localIp ?: "Unavailable"),
            "Traffic" to "↓ ${formatBytes(traffic.bytesIn)}  ↑ ${formatBytes(traffic.bytesOut)}",
        )
        is TunnelState.Connecting -> listOfNotNull("Status" to state.stage, blockedRow)
        is TunnelState.ScheduledPause -> listOfNotNull("Reconnecting in" to formatDuration((state.resumeAtMillis - now).coerceAtLeast(0)), blockedRow)
        is TunnelState.Disconnected -> listOfNotNull(blockedRow)
        else -> emptyList()
    }
    // A lone cell (connecting, paused) gets a narrower strip so it stays centred under the button.
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(if (cells.size <= 2) 0.5f * cells.size.coerceAtLeast(1) else 1f).height(IntrinsicSize.Min).heightIn(min = 48.dp),
    ) {
        cells.forEachIndexed { i, (label, value) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Plain.Raised))
            // Equal cells, so the dividers sit on a grid instead of following the text widths.
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Plain.Muted, maxLines = 1)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = Plain.Text, maxLines = 1)
            }
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
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEjmm")
            "Reconnect ${java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(Date(it))}"
        } ?: "Reconnect · no days selected",
    )
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
    is TunnelState.Disconnected -> if (blocking) "VPN apps are blocked until you reconnect." else "Apps use your normal internet connection."
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
