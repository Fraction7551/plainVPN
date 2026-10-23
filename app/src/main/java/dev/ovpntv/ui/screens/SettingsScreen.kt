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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.ovpntv.ui.components.plainIcon
import dev.ovpntv.ui.components.TvButton
import dev.ovpntv.R
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
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
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    // Back from the licences returns focus to their button; otherwise it would fall to the drawer.
    var returnFromLicenses by remember { mutableStateOf(false) }
    val licensesButton = remember { FocusRequester() }
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false; returnFromLicenses = true })
        return
    }
    LaunchedEffect(returnFromLicenses) {
        if (returnFromLicenses) runCatching { licensesButton.requestFocus() }
        returnFromLicenses = false
    }

    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding)) {
            ScreenTitle("Settings")
            SwitchCell(
                title = "Connect when the TV starts",
                subtitle = "Connects to the selected profile",
                checked = connectOnBoot,
                onCheckedChange = Settings::setConnectOnBoot,
            )
            SwitchCell(
                title = "Block VPN apps while reconnecting",
                subtitle = "VPN apps stay offline until it's back",
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
                    is UpdateState.NeedsPermission -> "Allow installs, then press again"
                    // The full reason is in Logs.
                    is UpdateState.Failed -> if (s.info != null) "Update failed. Press to retry" else "Couldn't check. Press to retry"
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
                subtitle = "Opens Android's VPN settings",
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
                "VPN engines" to "OpenVPN® 2.7.8",
                "" to "WireGuard® (wireguard-go)",
                "TLS library" to "Mbed TLS 3.6.7",
                "Compression" to "LZ4 1.10.0, LZO 2.10",
            ).forEach { (k, v) ->
                Row {
                    Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
                    Text(v, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(
                "OpenVPN® is a registered trademark of OpenVPN Inc. WireGuard® is a registered trademark of " +
                    "Jason A. Donenfeld. plainVPN is not affiliated with either.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Dimens.smallPadding))
            TvButton("Open-source licences", { showLicenses = true }, Modifier.focusRequester(licensesButton), icon = plainIcon(R.drawable.pi_info))
        }
    }
}
