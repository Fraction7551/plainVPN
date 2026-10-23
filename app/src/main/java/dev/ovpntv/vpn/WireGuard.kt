package dev.ovpntv.vpn

import com.wireguard.android.backend.GoBackend
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import dev.ovpntv.data.InvalidProfileException
import java.io.BufferedReader
import java.io.StringReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * WireGuard profiles: the `.conf` files WireGuard apps and VPN providers hand out
 * (an `[Interface]` section plus one or more `[Peer]` sections).
 */
object WireGuardProfile {
    /** The keys the tunnel library's parser accepts; anything else makes it reject the file. */
    private val INTERFACE_KEYS = setOf("address", "dns", "listenport", "mtu", "privatekey")
    private val PEER_KEYS = setOf("allowedips", "endpoint", "persistentkeepalive", "presharedkey", "publickey")

    /** True when [text] is a WireGuard profile rather than an OpenVPN one. */
    fun looksLikeWireGuard(text: String) = text.lineSequence().any { it.trim().equals("[Interface]", ignoreCase = true) }

    /** [text] with only what the engine understands, and the names of the keys or sections that were dropped. */
    class Cleaned(val text: String, val dropped: List<String>)

    /**
     * Keeps the keys plainVPN's WireGuard engine uses. wg-quick extras (PostUp, PreDown and the
     * like, which are shell commands, plus Table, SaveConfig, FwMark) are dropped, and so are
     * IncludedApplications/ExcludedApplications: plainVPN's own split tunnelling decides that.
     */
    fun clean(text: String): Cleaned {
        val out = StringBuilder()
        val dropped = mutableListOf<String>()
        var section: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> out.appendLine(raw)
                line.startsWith("[") && line.endsWith("]") -> {
                    section = line.substring(1, line.length - 1).trim().lowercase()
                    if (section == "interface" || section == "peer") out.appendLine(raw)
                    else dropped += safeName(section).let { if (it == UNREADABLE) it else "[$it] section" }
                }
                else -> {
                    val key = line.substringBefore('=').trim().replace(" ", "").lowercase()
                    val allowed = when (section) {
                        "interface" -> INTERFACE_KEYS
                        "peer" -> PEER_KEYS
                        else -> emptySet()
                    }
                    if (line.contains('=') && key in allowed) out.appendLine(raw)
                    else if (section == "interface" || section == "peer") dropped += safeName(line.substringBefore('=').trim())
                }
            }
        }
        return Cleaned(out.toString(), dropped.distinct())
    }

    private const val UNREADABLE = "a line that isn't \"Key = value\""

    /**
     * Dropped names end up in the log, so only plain key names are shown. A broken line such as
     * "PrivateKey <key>" with its "=" missing would otherwise put part of the private key there.
     */
    private fun safeName(name: String) = if (name.matches(Regex("[A-Za-z]{1,30}"))) name else UNREADABLE

    /** Parses [text] (cleaned first) with the tunnel library's own parser, exactly as the engine will see it. */
    fun parse(text: String): Config {
        val config = try {
            Config.parse(BufferedReader(StringReader(clean(text).text)))
        } catch (e: BadConfigException) {
            throw InvalidProfileException("The WireGuard profile has an error: ${describe(e)}")
        } catch (e: Exception) {
            throw InvalidProfileException("The WireGuard profile could not be read: ${e.message}")
        }
        if (config.peers.isEmpty()) throw InvalidProfileException("The WireGuard profile has no [Peer], so there is no server to connect to.")
        if (config.peers.none { it.endpoint.isPresent }) {
            throw InvalidProfileException("The WireGuard profile's [Peer] has no Endpoint, so plainVPN doesn't know which server to connect to.")
        }
        if (config.`interface`.addresses.isEmpty()) throw InvalidProfileException("The WireGuard profile has no Address for this device.")
        return config
    }

    /** "interface private key: invalid key" from the library's error parts. */
    private fun describe(e: BadConfigException): String {
        fun words(x: Enum<*>?) = x?.name?.lowercase()?.replace('_', ' ').orEmpty()
        return listOf(words(e.section), words(e.location)).filter { it.isNotBlank() && it != "top level" && it != "config" }.joinToString(" ")
            .let { where -> if (where.isEmpty()) words(e.reason) else "$where: ${words(e.reason)}" }
    }

    /** Host and port of the first peer's Endpoint, for display ("vpn.example.com", "51820"). */
    fun endpointOf(text: String): Pair<String?, String?> {
        var section: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) section = line.trim('[', ']').trim().lowercase()
            else if (section == "peer" && line.substringBefore('=').trim().equals("Endpoint", ignoreCase = true)) {
                val value = line.substringAfter('=').substringBefore('#').trim()
                return if (value.startsWith("[")) {
                    value.substringAfter('[').substringBefore(']') to value.substringAfter("]:", "").ifEmpty { null }
                } else {
                    value.substringBeforeLast(':') to value.substringAfterLast(':', "").ifEmpty { null }
                }
            }
        }
        return null to null
    }
}

/** Live numbers from the engine (wireguard-go's UAPI "get" output). */
data class WireGuardStats(val lastHandshakeEpochSeconds: Long, val rxBytes: Long, val txBytes: Long) {
    companion object {
        /** Newest handshake and total traffic across all peers. */
        fun parse(uapi: String): WireGuardStats {
            var handshake = 0L
            var rx = 0L
            var tx = 0L
            for (line in uapi.lineSequence()) {
                val key = line.substringBefore('=')
                val value = line.substringAfter('=', "").toLongOrNull() ?: continue
                when (key) {
                    "last_handshake_time_sec" -> handshake = maxOf(handshake, value)
                    "rx_bytes" -> rx += value
                    "tx_bytes" -> tx += value
                }
            }
            return WireGuardStats(handshake, rx, tx)
        }
    }
}

/**
 * The wireguard-go engine bundled in com.wireguard.android:tunnel (libwg-go.so).
 *
 * The library's own entry point, GoBackend, runs tunnels through a VpnService of its own,
 * which would bypass plainVPN's blocking, split tunnelling and scheduling. So its JNI
 * functions (private static methods of GoBackend, kept by proguard-rules.pro) are called
 * directly, and plainVPN's service opens and owns the tunnel. Pinned to one library version
 * in build.gradle.kts; a version that renames these fails loudly at the first connect.
 */
object WireGuardEngine {
    private val methods: Map<String, Method> by lazy {
        System.loadLibrary("wg-go")
        val int = Int::class.javaPrimitiveType!!
        val str = String::class.java
        mapOf(
            "wgTurnOn" to arrayOf(str, int, str),
            "wgTurnOff" to arrayOf(int),
            "wgGetSocketV4" to arrayOf(int),
            "wgGetSocketV6" to arrayOf(int),
            "wgGetConfig" to arrayOf(int),
            "wgVersion" to emptyArray(),
        ).mapValues { (name, types) -> GoBackend::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true } }
    }

    private fun call(name: String, vararg args: Any?): Any? = try {
        methods.getValue(name).invoke(null, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    /** Starts a tunnel on [tunFd] (ownership passes to the engine). Returns a handle, or a negative error. */
    fun turnOn(name: String, tunFd: Int, uapiSettings: String) = call("wgTurnOn", name, tunFd, uapiSettings) as Int
    fun turnOff(handle: Int) { call("wgTurnOff", handle) }
    fun socketV4(handle: Int) = call("wgGetSocketV4", handle) as Int
    fun socketV6(handle: Int) = call("wgGetSocketV6", handle) as Int
    fun stats(handle: Int): WireGuardStats? = (call("wgGetConfig", handle) as String?)?.let { WireGuardStats.parse(it) }
    fun version(): String? = runCatching { call("wgVersion") as String? }.getOrNull()
}
