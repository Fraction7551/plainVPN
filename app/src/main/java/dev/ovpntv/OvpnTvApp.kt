package dev.ovpntv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.data.Settings
import dev.ovpntv.schedule.ReconnectScheduler
import dev.ovpntv.update.Updater

class OvpnTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        ProfileRepository.init(this)
        ReconnectScheduler.installCrashRestore(this)
        createNotificationChannel()
        ReconnectScheduler.reschedule(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_desc) }
        val updates = NotificationChannel(
            Updater.NOTIFICATION_CHANNEL_ID,
            getString(R.string.updates_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.updates_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, updates))
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "vpn_status"
    }
}
