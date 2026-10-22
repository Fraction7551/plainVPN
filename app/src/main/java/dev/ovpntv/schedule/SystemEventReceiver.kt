package dev.ovpntv.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.data.Settings
import dev.ovpntv.vpn.OpenVpnService
import dev.ovpntv.vpn.VpnState

/** Re-arms the schedule after boot, updates and clock changes; optionally connects on boot. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReconnectScheduler.reschedule(context)

        val wanted = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Settings.connectOnBoot.value
            // An update kills the running tunnel and Android doesn't restart the service.
            Intent.ACTION_MY_PACKAGE_REPLACED -> Settings.wantConnected
            else -> false
        }
        if (wanted && ProfileRepository.get(Settings.selectedProfileId.value) != null &&
            VpnService.prepare(context) == null
        ) {
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) VpnState.log("App was updated, restoring the VPN")
            OpenVpnService.connect(context)
        } else if (intent.action in RESTORE_BLOCK_ACTIONS && Settings.keptBlockedError != null &&
            VpnService.prepare(context) == null
        ) {
            // VPN apps were held offline after an error; a reboot or update must not let them out.
            OpenVpnService.start(context, Intent(context, OpenVpnService::class.java).setAction(OpenVpnService.ACTION_RESTORE_BLOCK))
        }
    }

    companion object {
        private val RESTORE_BLOCK_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
