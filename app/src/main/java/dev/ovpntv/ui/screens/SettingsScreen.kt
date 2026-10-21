package dev.ovpntv.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ovpntv.BuildConfig
import dev.ovpntv.data.Settings
import dev.ovpntv.ui.components.ListCell
import dev.ovpntv.ui.components.ScreenTitle
import dev.ovpntv.ui.components.SectionHeader
import dev.ovpntv.ui.components.SwitchCell
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.update.UpdateState
import dev.ovpntv.update.Updater

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val connectOnBoot by Settings.connectOnBoot.collectAsState()
    val blockWhileReconnecting by Settings.blockWhileReconnecting.collectAsState()
    val update by Updater.state.collectAsState()

    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ScreenTitle("Settings")
            SwitchCell(
                title = "Connect when the TV starts",
                subtitle = "Uses the selected profile. VPN permission must have been granted once.",
                checked = connectOnBoot,
                onCheckedChange = Settings::setConnectOnBoot,
            )
            SwitchCell(
                title = "Block VPN apps while reconnecting",
                subtitle = "While the VPN connects, restarts or does its scheduled reconnect, apps that use it get no internet instead of your normal connection",
                checked = blockWhileReconnecting,
                onCheckedChange = Settings::setBlockWhileReconnecting,
            )
            ListCell(
                title = when (update) {
                    is UpdateState.Available, is UpdateState.NeedsPermission -> "Install update"
                    else -> "Check for updates"
                },
                subtitle = when (val s = update) {
                    UpdateState.Idle -> "You're on ${BuildConfig.VERSION_NAME}"
                    UpdateState.Checking -> "Checking..."
                    UpdateState.UpToDate -> "You're on the latest version"
                    is UpdateState.Available -> "Version ${s.info.versionName} is available"
                    is UpdateState.Downloading -> "Downloading ${s.info.versionName}... ${s.percent}%"
                    is UpdateState.Installing -> "Installing ${s.info.versionName}..."
                    is UpdateState.NeedsPermission -> "Allow OpenVPN TV to install apps, then press here again"
                    is UpdateState.Failed -> s.message
                },
                onClick = {
                    when (val s = update) {
                        is UpdateState.Available, is UpdateState.NeedsPermission -> Updater.install(context)
                        is UpdateState.Failed -> if (s.info != null) Updater.install(context) else Updater.check(context)
                        else -> Updater.check(context)
                    }
                },
            )
            ListCell(
                title = "Always-on VPN & kill switch",
                subtitle = "Open Android's VPN settings to make this app always-on and block traffic without VPN (where the TV supports it)",
                onClick = {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
            )
        }
        Spacer(Modifier.width(Dimens.largePadding))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding)) {
            SectionHeader("About")
            listOf(
                "App version" to BuildConfig.VERSION_NAME,
                "VPN engine" to "OpenVPN 2.7.8",
                "TLS library" to "mbed TLS 3.6.4",
                "Compression" to "LZ4 1.10.0, LZO 2.10",
            ).forEach { (k, v) ->
                Row {
                    Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
                    Text(v, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(
                "OpenVPN is a registered trademark of OpenVPN Inc. This app is not affiliated with OpenVPN Inc or Mullvad VPN AB.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
