package dev.ovpntv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-configured daily disconnect/reconnect cycle. */
data class ReconnectSchedule(
    val enabled: Boolean = false,
    val hour: Int = 4,
    val minute: Int = 0,
    /** Bitmask of days the cycle runs on, bit 0 = Monday ... bit 6 = Sunday. */
    val days: Int = ALL_DAYS,
    /** How long the tunnel stays down before reconnecting. */
    val offlineSeconds: Int = 10,
    /** Bring the tunnel up at the scheduled time even if it was off. */
    val connectIfDisconnected: Boolean = false,
) {
    fun runsOn(dayIndex: Int) = days and (1 shl dayIndex) != 0

    companion object {
        const val ALL_DAYS = 0b1111111
    }
}

enum class SplitMode {
    /** Every app uses the VPN. */
    Off,
    /** Only [SplitTunnel.apps] use the VPN. */
    OnlySelected,
    /** Every app except [SplitTunnel.apps] uses the VPN. */
    ExceptSelected,
}

/** Per-app split tunneling, applied when the tunnel is opened. */
data class SplitTunnel(val mode: SplitMode = SplitMode.Off, val apps: Set<String> = emptySet()) {
    val isActive get() = mode != SplitMode.Off && apps.isNotEmpty()
}

object Settings {
    private lateinit var prefs: SharedPreferences

    private val _schedule = MutableStateFlow(ReconnectSchedule())
    val schedule: StateFlow<ReconnectSchedule> = _schedule.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    private val _connectOnBoot = MutableStateFlow(false)
    val connectOnBoot: StateFlow<Boolean> = _connectOnBoot.asStateFlow()

    private val _splitTunnel = MutableStateFlow(SplitTunnel())
    val splitTunnel: StateFlow<SplitTunnel> = _splitTunnel.asStateFlow()

    /** Keep VPN apps offline while the tunnel is being (re)established instead of letting them go direct. */
    private val _blockWhileReconnecting = MutableStateFlow(true)
    val blockWhileReconnecting: StateFlow<Boolean> = _blockWhileReconnecting.asStateFlow()

    /** Whether the user wants the tunnel up; used to restore it after boot or a crash. */
    var wantConnected: Boolean
        get() = prefs.getBoolean(KEY_WANT_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_WANT_CONNECTED, value).apply()

    /**
     * Set while VPN apps are kept blocked after an error the user has to fix, so the block
     * can be put back after a reboot or app update. Holds the error shown on Home ("" if none).
     */
    var keptBlockedError: String?
        get() = if (prefs.getBoolean(KEY_KEPT_BLOCKED, false)) prefs.getString(KEY_KEPT_BLOCKED_ERROR, "") else null
        set(value) = prefs.edit()
            .putBoolean(KEY_KEPT_BLOCKED, value != null)
            .putString(KEY_KEPT_BLOCKED_ERROR, value.orEmpty())
            .apply()

    /**
     * Counts an automatic VPN restore after an app crash. Returns false once [max] restores
     * happened within [windowMs], so a crash loop gives up. Written synchronously because the
     * process is about to die.
     */
    fun allowCrashRestore(now: Long, windowMs: Long, max: Int): Boolean {
        val start = prefs.getLong(KEY_CRASH_WINDOW_START, 0)
        val count = if (now - start in 0 until windowMs) prefs.getInt(KEY_CRASH_COUNT, 0) else 0
        if (count >= max) return false
        prefs.edit()
            .putLong(KEY_CRASH_WINDOW_START, if (count == 0) now else start)
            .putInt(KEY_CRASH_COUNT, count + 1)
            .commit()
        return true
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _schedule.value = ReconnectSchedule(
            enabled = prefs.getBoolean(KEY_SCHED_ENABLED, false),
            hour = prefs.getInt(KEY_SCHED_HOUR, 4),
            minute = prefs.getInt(KEY_SCHED_MINUTE, 0),
            days = prefs.getInt(KEY_SCHED_DAYS, ReconnectSchedule.ALL_DAYS),
            offlineSeconds = prefs.getInt(KEY_SCHED_OFFLINE, 10),
            connectIfDisconnected = prefs.getBoolean(KEY_SCHED_CONNECT_IF_OFF, false),
        )
        _selectedProfileId.value = prefs.getString(KEY_SELECTED_PROFILE, null)
        _connectOnBoot.value = prefs.getBoolean(KEY_CONNECT_ON_BOOT, false)
        _blockWhileReconnecting.value = prefs.getBoolean(KEY_BLOCK_WHILE_RECONNECTING, true)
        _splitTunnel.value = SplitTunnel(
            mode = prefs.getString(KEY_SPLIT_MODE, null)
                ?.let { name -> SplitMode.entries.firstOrNull { it.name == name } } ?: SplitMode.Off,
            apps = prefs.getStringSet(KEY_SPLIT_APPS, null)?.toSet().orEmpty(),
        )
    }

    fun setSplitTunnel(split: SplitTunnel) {
        prefs.edit()
            .putString(KEY_SPLIT_MODE, split.mode.name)
            .putStringSet(KEY_SPLIT_APPS, split.apps)
            .apply()
        _splitTunnel.value = split
    }

    fun setSchedule(schedule: ReconnectSchedule) {
        prefs.edit()
            .putBoolean(KEY_SCHED_ENABLED, schedule.enabled)
            .putInt(KEY_SCHED_HOUR, schedule.hour)
            .putInt(KEY_SCHED_MINUTE, schedule.minute)
            .putInt(KEY_SCHED_DAYS, schedule.days)
            .putInt(KEY_SCHED_OFFLINE, schedule.offlineSeconds)
            .putBoolean(KEY_SCHED_CONNECT_IF_OFF, schedule.connectIfDisconnected)
            .apply()
        _schedule.value = schedule
    }

    fun setSelectedProfile(id: String?) {
        prefs.edit().putString(KEY_SELECTED_PROFILE, id).apply()
        _selectedProfileId.value = id
    }

    fun setConnectOnBoot(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECT_ON_BOOT, enabled).apply()
        _connectOnBoot.value = enabled
    }

    fun setBlockWhileReconnecting(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_WHILE_RECONNECTING, enabled).apply()
        _blockWhileReconnecting.value = enabled
    }

    private const val KEY_SCHED_ENABLED = "schedule_enabled"
    private const val KEY_SCHED_HOUR = "schedule_hour"
    private const val KEY_SCHED_MINUTE = "schedule_minute"
    private const val KEY_SCHED_DAYS = "schedule_days"
    private const val KEY_SCHED_OFFLINE = "schedule_offline_seconds"
    private const val KEY_SCHED_CONNECT_IF_OFF = "schedule_connect_if_off"
    private const val KEY_SELECTED_PROFILE = "selected_profile"
    private const val KEY_CONNECT_ON_BOOT = "connect_on_boot"
    private const val KEY_WANT_CONNECTED = "want_connected"
    private const val KEY_BLOCK_WHILE_RECONNECTING = "block_while_reconnecting"
    private const val KEY_KEPT_BLOCKED = "kept_blocked"
    private const val KEY_KEPT_BLOCKED_ERROR = "kept_blocked_error"
    private const val KEY_SPLIT_MODE = "split_mode"
    private const val KEY_SPLIT_APPS = "split_apps"
    private const val KEY_CRASH_WINDOW_START = "crash_restore_window_start"
    private const val KEY_CRASH_COUNT = "crash_restore_count"
}
