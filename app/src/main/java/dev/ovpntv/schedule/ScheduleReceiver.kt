package dev.ovpntv.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.data.Settings
import dev.ovpntv.vpn.OpenVpnService
import dev.ovpntv.vpn.VpnState

/** Receives the schedule's alarms and forwards them to [OpenVpnService]. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CYCLE -> {
                // Arm tomorrow's (or the next selected day's) alarm first.
                ReconnectScheduler.reschedule(context)
                val schedule = Settings.schedule.value
                val vpnUp = VpnState.isActive || Settings.wantConnected
                val canConnect = schedule.connectIfDisconnected &&
                    ProfileRepository.get(Settings.selectedProfileId.value) != null &&
                    VpnService.prepare(context) == null
                if (vpnUp || canConnect) {
                    OpenVpnService.start(context, Intent(context, OpenVpnService::class.java).setAction(OpenVpnService.ACTION_SCHEDULED_CYCLE))
                } else {
                    VpnState.log("Scheduled reconnect skipped: VPN is off")
                }
            }
            ACTION_RESUME -> if (Settings.wantConnected) {
                OpenVpnService.start(context, Intent(context, OpenVpnService::class.java).setAction(OpenVpnService.ACTION_RESUME))
            }
            // Set by the crash handler (ReconnectScheduler.installCrashRestore).
            ACTION_RESTORE -> if (Settings.wantConnected || Settings.keptBlockedError != null) {
                OpenVpnService.start(context, Intent(context, OpenVpnService::class.java).setAction(OpenVpnService.ACTION_RESTORE))
            }
        }
    }

    companion object {
        const val ACTION_CYCLE = "dev.ovpntv.schedule.CYCLE"
        const val ACTION_RESUME = "dev.ovpntv.schedule.RESUME"
        const val ACTION_RESTORE = "dev.ovpntv.schedule.RESTORE"
    }
}
