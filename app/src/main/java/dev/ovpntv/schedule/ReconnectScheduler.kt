package dev.ovpntv.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.ovpntv.data.ReconnectSchedule
import dev.ovpntv.data.Settings
import dev.ovpntv.ui.MainActivity
import dev.ovpntv.vpn.VpnState
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Schedules the daily "disconnect, wait, reconnect" cycle.
 *
 * Alarm-clock alarms are used because they fire on time even when the TV box is
 * idle (Doze), unlike inexact or allow-while-idle alarms which can be deferred.
 */
object ReconnectScheduler {
    private const val REQUEST_CYCLE = 100
    private const val REQUEST_RESUME = 101
    private const val REQUEST_RESTORE = 102
    private const val CRASH_RESTORE_DELAY_MS = 3_000L
    private const val CRASH_RESTORE_WINDOW_MS = 10 * 60_000L
    private const val CRASH_RESTORE_MAX = 3

    /** Re-arms (or cancels) the next cycle alarm from the saved schedule. */
    fun reschedule(context: Context) {
        val schedule = Settings.schedule.value
        val pending = cyclePendingIntent(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending)
        if (!schedule.enabled || schedule.days == 0) return

        val at = nextTrigger(schedule, System.currentTimeMillis()) ?: return
        setAlarm(context, at, pending)
        VpnState.log("Next scheduled reconnect: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))}")
    }

    fun scheduleResume(context: Context, at: Long) {
        setAlarm(context, at, resumePendingIntent(context))
    }

    fun cancelResume(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(resumePendingIntent(context))
    }

    /**
     * Android doesn't restart the sticky VPN service after an uncaught exception, so the
     * tunnel would stay down (and VPN apps go direct) until plainVPN is opened again. On a
     * crash while the tunnel is wanted, an alarm brings it back a few seconds later; a crash
     * loop gives up after [CRASH_RESTORE_MAX] restores in [CRASH_RESTORE_WINDOW_MS].
     * Native crashes (e.g. in wireguard-go) never reach this handler.
     */
    fun installCrashRestore(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val now = System.currentTimeMillis()
                if ((Settings.wantConnected || Settings.keptBlockedError != null) &&
                    Settings.allowCrashRestore(now, CRASH_RESTORE_WINDOW_MS, CRASH_RESTORE_MAX)
                ) {
                    setAlarm(app, now + CRASH_RESTORE_DELAY_MS, restorePendingIntent(app))
                }
            } catch (_: Throwable) {
                // Never let the handler hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Next time at or after [now] matching the schedule's time and days, or null if no day is selected. */
    fun nextTrigger(schedule: ReconnectSchedule, now: Long): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, schedule.hour)
            set(Calendar.MINUTE, schedule.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cal.timeInMillis > now && schedule.runsOn(dayIndex(cal))) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            // Re-apply the wall-clock time in case a DST change shifted it.
            cal.set(Calendar.HOUR_OF_DAY, schedule.hour)
            cal.set(Calendar.MINUTE, schedule.minute)
        }
        return null
    }

    /** Monday = 0 ... Sunday = 6. */
    fun dayIndex(cal: Calendar) = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setAlarm(context: Context, at: Long, operation: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        } else {
            VpnState.log("Exact alarms not permitted; the scheduled reconnect may run a few minutes late")
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun cyclePendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CYCLE,
        Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_CYCLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun resumePendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_RESUME,
        Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_RESUME),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun restorePendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_RESTORE,
        Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_RESTORE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
