package dev.ovpntv.vpn

import dev.ovpntv.data.SplitTunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface TunnelState {
    data class Disconnected(val error: String? = null) : TunnelState
    data class Connecting(val stage: String, val profileName: String) : TunnelState
    data class Connected(
        val profileName: String,
        val localIp: String?,
        val serverIp: String?,
        val sinceMillis: Long,
    ) : TunnelState
    data object Disconnecting : TunnelState
    /** Tunnel taken down by the reconnect schedule; it comes back at [resumeAtMillis]. */
    data class ScheduledPause(val profileName: String, val resumeAtMillis: Long) : TunnelState
}

data class Traffic(val bytesIn: Long = 0, val bytesOut: Long = 0)

/** Process-wide VPN state, written by [OpenVpnService] and observed by the UI. */
object VpnState {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    /** Split tunnel the running tunnel was opened with; the UI compares it to the saved setting. */
    private val _appliedSplit = MutableStateFlow<SplitTunnel?>(null)
    val appliedSplit: StateFlow<SplitTunnel?> = _appliedSplit.asStateFlow()

    /** True while a placeholder tunnel keeps VPN apps offline until the real one is back. */
    private val _blocking = MutableStateFlow(false)
    val blocking: StateFlow<Boolean> = _blocking.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val isActive: Boolean
        get() = when (_state.value) {
            is TunnelState.Disconnected -> false
            else -> true
        }

    internal fun set(state: TunnelState) {
        _state.value = state
        if (state is TunnelState.Disconnected) _appliedSplit.value = null
    }

    internal fun setAppliedSplit(split: SplitTunnel?) {
        _appliedSplit.value = split
    }

    internal fun setBlocking(blocking: Boolean) {
        _blocking.value = blocking
    }

    internal fun setTraffic(traffic: Traffic) {
        _traffic.value = traffic
    }

    fun log(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        synchronized(this) {
            val current = _log.value
            _log.value = if (current.size >= MAX_LOG_LINES) current.drop(current.size - MAX_LOG_LINES + 1) + stamped
            else current + stamped
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private const val MAX_LOG_LINES = 600
}
