package dev.ovpntv.vpn

import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Collects the IFCONFIG/ROUTE/DNS requests OpenVPN sends over the management
 * interface (doc/android.txt) and turns them into a VpnService.Builder.
 */
class TunConfig {
    private data class Cidr(val address: String, val prefix: Int)

    private var local4: Cidr? = null
    private var local6: Cidr? = null
    private var mtu = 1500
    private val routes4 = mutableListOf<Cidr>()
    private val routes6 = mutableListOf<Cidr>()
    private val excluded4 = mutableListOf<Cidr>()
    private val excluded6 = mutableListOf<Cidr>()
    private val dns = mutableListOf<String>()
    private var domain: String? = null
    private var proxy: Pair<String, Int>? = null

    /** "local remoteOrNetmask mtu topology" */
    fun ifconfig(msg: String) {
        val p = msg.split(" ")
        val local = p[0]
        val other = p.getOrNull(1).orEmpty()
        p.getOrNull(2)?.toIntOrNull()?.let { mtu = it }
        val topology = p.getOrNull(3) ?: "undef"
        local4 = when {
            topology == "subnet" || (topology == "undef" && other.startsWith("255.")) ->
                Cidr(local, netmaskToPrefix(other))
            topology == "net30" -> Cidr(local, 30)
            else -> {
                // Point-to-point: the peer address is reached through the tunnel.
                if (other.isNotEmpty()) routes4 += Cidr(other, 32)
                Cidr(local, 32)
            }
        }
    }

    /** "addr/bits mtu" */
    fun ifconfig6(msg: String) {
        val p = msg.split(" ")
        local6 = parseCidr(p[0], 128)
        p.getOrNull(1)?.toIntOrNull()?.let { mtu = it }
    }

    /** "network netmask gateway [dev iface]" */
    fun route(msg: String) {
        val p = msg.split(" ")
        val cidr = Cidr(p[0], netmaskToPrefix(p.getOrElse(1) { "255.255.255.255" }))
        if (isBypassRoute(msg)) excluded4 += cidr else routes4 += cidr
    }

    /** "network/bits device" */
    fun route6(msg: String) {
        val p = msg.split(" ")
        val cidr = parseCidr(p[0], 128)
        val dev = p.getOrNull(1)
        if (dev != null && dev != TUN_NAME && !dev.startsWith("tun")) excluded6 += cidr else routes6 += cidr
    }

    fun dns(server: String) {
        dns += server
    }

    fun domain(name: String) {
        domain = name
    }

    /** "host port" */
    fun httpProxy(msg: String) {
        val p = msg.split(" ")
        val port = p.getOrNull(1)?.toIntOrNull() ?: return
        proxy = p[0] to port
    }

    fun apply(builder: VpnService.Builder, log: (String) -> Unit) {
        builder.setMtu(mtu)
        local4?.let { builder.addAddress(it.address, it.prefix) }
        local6?.let { builder.addAddress(it.address, it.prefix) }

        for (r in routes4) addRoute(builder, r, log)
        for (r in routes6) addRoute(builder, r, log)

        if (excluded4.isNotEmpty() || excluded6.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for (r in excluded4 + excluded6) {
                    runCatching { builder.excludeRoute(IpPrefix(networkAddress(r.address, r.prefix), r.prefix)) }
                        .onFailure { log("Could not exclude route ${r.address}/${r.prefix}: ${it.message}") }
                }
            } else {
                log("Ignoring ${excluded4.size + excluded6.size} bypass route(s); Android 13+ is needed for route exclusion")
            }
        }

        dns.forEach { server ->
            runCatching { builder.addDnsServer(server) }.onFailure { log("Bad DNS server $server") }
        }
        domain?.let { builder.addSearchDomain(it) }
        proxy?.let { (host, port) ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port))
            }
        }

        // Android blocks any address family that has no address or route. Keep
        // that for IPv6 when all IPv4 traffic is tunnelled (prevents IPv6 leaks),
        // but let both families through untouched for split-tunnel profiles.
        val redirectsAll4 = routes4.any { it.prefix == 0 } ||
            (routes4.any { it.address == "0.0.0.0" && it.prefix == 1 } &&
                routes4.any { it.address == "128.0.0.0" && it.prefix == 1 })
        if (!redirectsAll4) {
            if (local4 == null) builder.allowFamily(OsConstants.AF_INET)
            if (local6 == null && routes6.isEmpty()) builder.allowFamily(OsConstants.AF_INET6)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        log(describe())
    }

    private fun addRoute(builder: VpnService.Builder, r: Cidr, log: (String) -> Unit) {
        runCatching { builder.addRoute(networkAddress(r.address, r.prefix), r.prefix) }
            .onFailure { log("Could not add route ${r.address}/${r.prefix}: ${it.message}") }
    }

    fun describe(): String = buildString {
        append("Tunnel: ")
        local4?.let { append("ip ${it.address}/${it.prefix} ") }
        local6?.let { append("ip6 ${it.address}/${it.prefix} ") }
        append("mtu $mtu, ")
        append("routes ${(routes4 + routes6).joinToString { "${it.address}/${it.prefix}" }}")
        if (dns.isNotEmpty()) append(", dns ${dns.joinToString()}")
    }

    companion object {
        const val TUN_NAME = "vpnservice-tun"

        /**
         * Pseudo default gateway (127.'d'.'g'.'w') that OpenVPN reports on Android
         * (route.c get_default_gateway). Only routes via this gateway — e.g. the
         * server's own address or "net_gateway" routes — bypass the tunnel. Every
         * ROUTE message carries "dev android-gw", so "dev" itself means nothing.
         */
        const val NET_GATEWAY = "127.100.103.119"

        /** True for a ROUTE message ("network netmask gateway [dev iface]") that must skip the tunnel. */
        fun isBypassRoute(msg: String) = msg.split(" ").getOrNull(2) == NET_GATEWAY

        private fun parseCidr(text: String, defaultPrefix: Int): Cidr {
            val slash = text.indexOf('/')
            return if (slash < 0) Cidr(text, defaultPrefix)
            else Cidr(text.substring(0, slash), text.substring(slash + 1).toIntOrNull() ?: defaultPrefix)
        }

        fun netmaskToPrefix(mask: String): Int {
            val addr = runCatching { InetAddress.getByName(mask) }.getOrNull() as? Inet4Address ?: return 32
            return addr.address.sumOf { Integer.bitCount(it.toInt() and 0xff) }
        }

        /** Clears host bits; VpnService.Builder rejects routes that have them set. */
        fun networkAddress(address: String, prefix: Int): InetAddress {
            val addr = InetAddress.getByName(address)
            val bytes = addr.address
            for (i in bytes.indices) {
                val bitsInByte = (prefix - i * 8).coerceIn(0, 8)
                val mask = if (bitsInByte == 0) 0 else (0xff shl (8 - bitsInByte)) and 0xff
                bytes[i] = (bytes[i].toInt() and mask).toByte()
            }
            return if (addr is Inet6Address) Inet6Address.getByAddress(null, bytes, null)
            else InetAddress.getByAddress(bytes)
        }
    }
}
