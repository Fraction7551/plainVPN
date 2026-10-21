package dev.ovpntv.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ovpntv.data.Settings
import dev.ovpntv.data.SplitMode
import dev.ovpntv.data.SplitTunnel
import dev.ovpntv.ui.components.ListCell
import dev.ovpntv.ui.components.ScreenTitle
import dev.ovpntv.ui.components.SectionHeader
import dev.ovpntv.ui.components.TvButton
import dev.ovpntv.R
import dev.ovpntv.ui.components.plainIcon
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.theme.Plain
import dev.ovpntv.ui.theme.warning
import dev.ovpntv.vpn.TunnelState
import dev.ovpntv.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val packageName: String, val label: String)

private val MODES = listOf(
    Triple(SplitMode.Off, "All apps use the VPN", "Split tunneling is off"),
    Triple(SplitMode.OnlySelected, "Only selected apps use the VPN", "Everything else uses your normal connection"),
    Triple(SplitMode.ExceptSelected, "Selected apps bypass the VPN", "Everything else uses the VPN"),
)

@Composable
fun SplitTunnelScreen(onConnect: (String?) -> Unit) {
    val context = LocalContext.current
    val split by Settings.splitTunnel.collectAsState()
    val applied by VpnState.appliedSplit.collectAsState()
    val state by VpnState.state.collectAsState()
    val apps by produceState<List<InstalledApp>?>(null) { value = withContext(Dispatchers.IO) { loadApps(context) } }

    // Selected apps first, so the current choice is visible without scrolling.
    val sorted = remember(apps, split.apps) {
        apps?.sortedWith(compareBy<InstalledApp> { it.packageName !in split.apps }.thenBy { it.label.lowercase() })
    }

    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding)) {
            ScreenTitle("Split tunneling")
            Text(
                "Choose which apps send their traffic through the VPN. Changes apply the next time the VPN connects.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.mediumPadding),
            )
            MODES.forEach { (mode, title, subtitle) ->
                ListCell(
                    title = title,
                    subtitle = subtitle,
                    selected = split.mode == mode,
                    onClick = { Settings.setSplitTunnel(split.copy(mode = mode)) },
                )
            }

            if (split.mode != SplitMode.Off && split.apps.isEmpty()) {
                Text(
                    "No apps selected yet, so every app uses the VPN.",
                    color = MaterialTheme.colorScheme.warning,
                    modifier = Modifier.padding(top = Dimens.mediumPadding),
                )
            }

            val connected = state is TunnelState.Connected || state is TunnelState.Connecting
            if (connected && applied != null && !sameEffect(applied!!, split)) {
                Spacer(Modifier.padding(top = Dimens.mediumPadding))
                Text("The running connection still uses the previous app selection.", color = MaterialTheme.colorScheme.warning)
                Spacer(Modifier.padding(top = Dimens.smallPadding))
                TvButton("Reconnect to apply", { onConnect(null) }, icon = plainIcon(R.drawable.pi_refresh))
            }
        }

        Spacer(Modifier.width(Dimens.largePadding))

        Column(Modifier.weight(1f)) {
            SectionHeader(
                when (split.mode) {
                    SplitMode.Off -> "Apps (turn on a mode to pick)"
                    SplitMode.OnlySelected -> "Apps that use the VPN"
                    SplitMode.ExceptSelected -> "Apps that bypass the VPN"
                },
            )
            when (val list = sorted) {
                null -> Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding), contentPadding = PaddingValues(Dimens.focusOutset)) {
                    items(list, key = { it.packageName }) { app ->
                        val checked = app.packageName in split.apps
                        ListCell(
                            title = app.label,
                            subtitle = app.packageName,
                            selected = checked,
                            containerColor = if (split.mode == SplitMode.Off) Plain.Canvas else Plain.Surface,
                            onClick = {
                                val apps = if (checked) split.apps - app.packageName else split.apps + app.packageName
                                // Picking an app while off implies the user wants "only selected".
                                val mode = if (split.mode == SplitMode.Off && !checked) SplitMode.OnlySelected else split.mode
                                Settings.setSplitTunnel(SplitTunnel(mode, apps))
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Whether two settings route traffic the same way (an inactive split equals "off"). */
private fun sameEffect(a: SplitTunnel, b: SplitTunnel) =
    (!a.isActive && !b.isActive) || (a.isActive && b.isActive && a == b)

private fun loadApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        .flatMap { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(it), 0) }
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
}
