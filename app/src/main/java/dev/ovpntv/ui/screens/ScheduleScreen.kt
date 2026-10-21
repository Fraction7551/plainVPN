package dev.ovpntv.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ovpntv.data.ReconnectSchedule
import dev.ovpntv.data.Settings
import dev.ovpntv.schedule.ReconnectScheduler
import dev.ovpntv.ui.components.ScreenTitle
import dev.ovpntv.ui.components.SectionHeader
import dev.ovpntv.ui.components.SwitchCell
import dev.ovpntv.ui.components.TvButton
import dev.ovpntv.ui.components.TvChip
import dev.ovpntv.ui.components.focusRing
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.theme.Plain
import dev.ovpntv.ui.theme.positive
import dev.ovpntv.ui.theme.warning
import java.text.DateFormat
import java.util.Date

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val OFFLINE_OPTIONS = listOf(5, 10, 30, 60, 300, 900, 1800, 3600)

@Composable
fun ScheduleScreen() {
    val context = LocalContext.current
    val schedule by Settings.schedule.collectAsState()

    fun update(new: ReconnectSchedule) {
        Settings.setSchedule(new)
        ReconnectScheduler.reschedule(context)
    }

    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.weight(1.3f)) {
            // Title and intro stay put; only the controls scroll, so focusing the first
            // switch can't scroll the title off the top.
            ScreenTitle("Scheduled reconnect")
            Text(
                "At the chosen time the VPN disconnects, stays off for the selected period, then reconnects by itself. " +
                    "Useful for picking up a fresh server, clearing a stale session or matching a router's maintenance window.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.smallPadding),
            )
            Column(
                // Padding inside the scroll so focus rings at the edges aren't clipped.
                Modifier.verticalScroll(rememberScrollState()).padding(Dimens.focusOutset),
                verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding),
            ) {
                SwitchCell(
                    title = "Automatic disconnect & reconnect",
                    checked = schedule.enabled,
                    onCheckedChange = { update(schedule.copy(enabled = it)) },
                )
                SwitchCell(
                    title = "Connect at this time if the VPN is off",
                    subtitle = "Otherwise the schedule only cycles a connection that is already running",
                    checked = schedule.connectIfDisconnected,
                    onCheckedChange = { update(schedule.copy(connectIfDisconnected = it)) },
                )

                SectionHeader("Time")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberStepper(schedule.hour, 0..23) { update(schedule.copy(hour = it)) }
                    Text(":", style = MaterialTheme.typography.displaySmall, fontSize = 40.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
                    NumberStepper(schedule.minute, 0..59, step = 5) { update(schedule.copy(minute = it)) }
                    Text(
                        "Press OK on a number, change it with ▲ / ▼, press OK again to finish",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Dimens.mediumPadding),
                    )
                }

                // Fixed rows of fixed-width chips: selecting one only changes its colour,
                // nothing reflows (Mon-Thu / Fri-Sun, then 4 + 4 durations).
                SectionHeader("Days")
                ChipRows(DAY_LABELS.indices.toList(), perRow = 4) { i ->
                    TvChip(DAY_LABELS[i], schedule.runsOn(i), { update(schedule.copy(days = schedule.days xor (1 shl i))) }, Modifier.width(ChipWidth), compact = true)
                }

                SectionHeader("Stay disconnected for")
                ChipRows(OFFLINE_OPTIONS, perRow = 4) { seconds ->
                    TvChip(formatSeconds(seconds), schedule.offlineSeconds == seconds, { update(schedule.copy(offlineSeconds = seconds)) }, Modifier.width(ChipWidth), compact = true)
                }
                Spacer(Modifier.size(Dimens.largePadding))
            }
        }

        Spacer(Modifier.width(Dimens.largePadding))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.mediumPadding)) {
            SectionHeader("Summary")
            val next = ReconnectScheduler.nextTrigger(schedule, System.currentTimeMillis())
            val summary = when {
                !schedule.enabled -> "Scheduled reconnect is off."
                next == null -> "Pick at least one day."
                else -> "Next run: ${DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(next))}.\n\n" +
                    "The VPN will disconnect, stay off for ${formatSeconds(schedule.offlineSeconds)}, then reconnect to the selected profile." +
                    if (schedule.connectIfDisconnected) " If the VPN is off at that time it will be switched on." else ""
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodyLarge,
                color = if (schedule.enabled && next != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (schedule.enabled && schedule.offlineSeconds >= 60) {
                Text(
                    "While disconnected, apps use your normal internet connection without VPN protection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.warning,
                )
            }
            if (schedule.enabled && !ReconnectScheduler.canScheduleExact(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text("Exact alarms are blocked, so the schedule may run late.", color = MaterialTheme.colorScheme.warning)
                TvButton("Allow exact alarms", {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }
    }
}

private val ChipWidth = 98.dp

@Composable
private fun <T> ChipRows(items: List<T>, perRow: Int, chip: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding)) {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.smallPadding)) { row.forEach { chip(it) } }
        }
    }
}

/**
 * Large focusable number. Press OK to start editing, then D-pad up/down changes
 * the value (wrapping around [range]); OK or Back finishes. While not editing,
 * up/down move focus as usual so the stepper never traps the D-pad.
 */
@Composable
private fun NumberStepper(value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var editing by remember { mutableStateOf(false) }
    if (!focused && editing) editing = false
    val size = range.last - range.first + 1
    fun shift(by: Int) = onChange(((value - range.first + by) % size + size) % size + range.first)
    // Editing turns the number blue, so it reads as "live" without relying on the arrows alone.
    val bg = when {
        editing -> Plain.Primary
        focused -> Plain.Raised
        else -> Plain.Surface
    }
    val arrowTint = if (editing) Plain.Primary else Plain.Muted.copy(alpha = if (focused) 1f else 0.3f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.KeyboardArrowUp, null, tint = arrowTint)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 96.dp, height = 68.dp)
                .focusRing(focused, 16.dp)
                .background(bg, Dimens.cardShape)
                .onPreviewKeyEvent { e ->
                    if (!editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionUp -> { shift(step); true }
                        Key.DirectionDown -> { shift(-step); true }
                        Key.Back, Key.Escape -> { editing = false; true }
                        else -> false
                    }
                }
                .clickable(interactionSource = interaction, indication = null) { editing = !editing },
        ) {
            Text("%02d".format(value), style = MaterialTheme.typography.displaySmall, fontSize = 40.sp, color = if (editing) Plain.OnPrimary else Plain.Text)
        }
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = arrowTint)
    }
}

private fun formatSeconds(seconds: Int) = when {
    seconds < 60 -> "$seconds sec"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} hour"
}
