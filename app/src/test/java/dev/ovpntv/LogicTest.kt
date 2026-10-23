package dev.ovpntv

import dev.ovpntv.data.ConfigInfo
import dev.ovpntv.data.InvalidProfileException
import dev.ovpntv.data.Profile
import dev.ovpntv.data.ProfileKind
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.importer.LanImportServer
import dev.ovpntv.data.ReconnectSchedule
import dev.ovpntv.schedule.ReconnectScheduler
import dev.ovpntv.vpn.ConfigSanitizer
import dev.ovpntv.vpn.ManagementClient
import dev.ovpntv.vpn.TunConfig
import dev.ovpntv.vpn.WireGuardProfile
import dev.ovpntv.vpn.WireGuardStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LogicTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun nextTriggerLaterToday() {
        // Saturday 17 Oct 2026, 03:00 -> today 04:00
        val s = ReconnectSchedule(enabled = true, hour = 4, minute = 0)
        assertEquals(at(2026, 10, 17, 4, 0), ReconnectScheduler.nextTrigger(s, at(2026, 10, 17, 3, 0)))
    }

    @Test
    fun nextTriggerRollsToTomorrowWhenPassed() {
        val s = ReconnectSchedule(enabled = true, hour = 4, minute = 0)
        assertEquals(at(2026, 10, 18, 4, 0), ReconnectScheduler.nextTrigger(s, at(2026, 10, 17, 4, 0)))
    }

    @Test
    fun nextTriggerHonoursSelectedDays() {
        // Only Monday (bit 0). From Saturday 17 Oct 2026 -> Monday 19 Oct.
        val s = ReconnectSchedule(enabled = true, hour = 2, minute = 30, days = 0b0000001)
        assertEquals(at(2026, 10, 19, 2, 30), ReconnectScheduler.nextTrigger(s, at(2026, 10, 17, 12, 0)))
    }

    @Test
    fun nextTriggerNullWithNoDays() {
        assertNull(ReconnectScheduler.nextTrigger(ReconnectSchedule(enabled = true, days = 0), at(2026, 10, 17, 12, 0)))
    }

    @Test
    fun dayIndexMondayIsZero() {
        val cal = Calendar.getInstance().apply { timeInMillis = at(2026, 10, 19, 12, 0) } // a Monday
        assertEquals(0, ReconnectScheduler.dayIndex(cal))
        cal.add(Calendar.DAY_OF_YEAR, 6)
        assertEquals(6, ReconnectScheduler.dayIndex(cal))
    }

    @Test
    fun netmaskToPrefix() {
        assertEquals(24, TunConfig.netmaskToPrefix("255.255.255.0"))
        assertEquals(1, TunConfig.netmaskToPrefix("128.0.0.0"))
        assertEquals(0, TunConfig.netmaskToPrefix("0.0.0.0"))
        assertEquals(32, TunConfig.netmaskToPrefix("255.255.255.255"))
    }

    @Test
    fun networkAddressClearsHostBits() {
        assertEquals("10.8.0.0", TunConfig.networkAddress("10.8.0.6", 24).hostAddress)
        assertEquals("192.168.0.0", TunConfig.networkAddress("192.168.1.77", 22).hostAddress)
        assertEquals("2001:db8:0:0:0:0:0:0", TunConfig.networkAddress("2001:db8::1234", 64).hostAddress)
    }

    @Test
    fun onlyNetGatewayRoutesBypassTheTunnel() {
        // redirect-gateway def1 as OpenVPN 2.7 sends it on Android.
        assertFalse(TunConfig.isBypassRoute("0.0.0.0 128.0.0.0 10.96.0.1 dev android-gw"))
        assertFalse(TunConfig.isBypassRoute("128.0.0.0 128.0.0.0 10.96.0.1 dev android-gw"))
        assertFalse(TunConfig.isBypassRoute("10.8.0.0 255.255.255.0 10.8.0.1"))
        assertTrue(TunConfig.isBypassRoute("192.168.0.0 255.255.255.0 127.100.103.119 dev android-gw"))
    }

    @Test
    fun sanitizerDropsScriptsAndRewritesAuthFile() {
        val input = """
            client
            dev tun
            remote vpn.example.com 1194 udp
            auth-user-pass /etc/openvpn/creds.txt
            script-security 2
            up /etc/openvpn/update-resolv-conf
            block-outside-dns
            persist-tun
            <ca>
            log not-a-directive-inside-a-block
            </ca>
        """.trimIndent()
        val out = ConfigSanitizer.sanitize(input).lines().map { it.trim() }
        assertTrue("auth-user-pass" in out)
        assertFalse(out.any { it.startsWith("up ") || it.startsWith("script-security") || it == "block-outside-dns" })
        assertTrue("persist-tun" in out)
        assertTrue("log not-a-directive-inside-a-block" in out)
        assertTrue("remote vpn.example.com 1194 udp" in out)
    }

    /** Directive names as OpenVPN reads the sanitized output. */
    private fun liveDirectives(config: String) = ConfigSanitizer.directives(ConfigSanitizer.sanitize(config)).map { it[0] }.toList()

    @Test
    fun sanitizerCatchesQuotedAndEscapedDirectives() {
        val input = """
            remote vpn.example.com 1194
            "script-security" 2
            'up' /system/bin/sh
            scr\ipt-security 2
            "--route-up" /system/bin/sh
            "--plugin" /data/local/tmp/x.so
              config /sdcard/other.ovpn
        """.trimIndent()
        val live = liveDirectives(input)
        assertEquals(listOf("remote"), live)
        // OpenVPN skips a UTF-8 byte-order mark before the first directive.
        assertEquals(listOf("remote"), liveDirectives(0xFEFF.toChar() + "up /system/bin/sh\nremote x 1194\n"))
    }

    @Test
    fun sanitizerUnwrapsSetenvOpt() {
        // OpenVPN runs "setenv opt X ..." as X, so X is what gets judged.
        val input = "remote x 1194\nsetenv opt log /data/data/dev.ovpntv/shared_prefs/settings.xml\n" +
            "setenv opt plugin /x.so\n\"setenv\" opt \"--config\" /sdcard/a.ovpn\nsetenv opt block-outside-dns\n" +
            "setenv opt auth-user-pass /creds\nsetenv FOO bar\n"
        val out = ConfigSanitizer.sanitize(input)
        assertEquals(listOf("remote", "auth-user-pass", "setenv"), liveDirectives(input))
        assertTrue(out.lines().any { it == "auth-user-pass" })
        assertFalse(out.contains("/creds"))
    }

    @Test
    fun lanImportOnlyAcceptsIpHosts() {
        assertTrue(LanImportServer.isIpHost("192.168.1.50:8080"))
        assertTrue(LanImportServer.isIpHost("10.0.0.5"))
        assertTrue(LanImportServer.isIpHost("[fd00::1]:8080"))
        assertFalse(LanImportServer.isIpHost("evil.example.com:8080"))
        assertFalse(LanImportServer.isIpHost("192.168.1.50.nip.io"))
        assertFalse(LanImportServer.isIpHost(null))
        assertFalse(LanImportServer.isIpHost(""))
    }

    @Test
    fun importedFilesAreSizeLimited() {
        assertEquals("abc", ProfileRepository.readProfileText("abc".byteInputStream()))
        val big = ByteArray(ProfileRepository.MAX_PROFILE_BYTES + 1) { 'a'.code.toByte() }
        assertTrue(runCatching { ProfileRepository.readProfileText(big.inputStream()) }.exceptionOrNull() is InvalidProfileException)
    }

    @Test
    fun sanitizerTagsMatchOpenVpn() {
        // OpenVPN ends a block at the first line that starts with the close tag, junk after it included.
        val input = "remote x 1194\n<ca>\nAAAA\n</ca> trailing junk\nup /system/bin/sh\n"
        assertEquals(listOf("remote"), liveDirectives(input))
        // An opening tag that is not alone on its line is not a block for OpenVPN either.
        assertEquals(listOf("remote", "<ca"), liveDirectives("remote x 1194\n<ca x>\nup /system/bin/sh\n").filter { it != "up" })
        assertFalse("up" in liveDirectives("remote x 1194\n<ca x>\nup /system/bin/sh\n"))
    }

    @Test
    fun sanitizerDropsLinesOpenVpnWouldSplit() {
        // OpenVPN reads 256-byte chunks, so the tail of a long comment would become a directive.
        val longComment = "#" + "a".repeat(255) + "script-security 2"
        val out = ConfigSanitizer.sanitize("remote x 1194\n$longComment\n")
        assertTrue(out.lines().all { it.toByteArray().size <= 250 })
        // A removed line must not grow past the limit either.
        val longPlugin = "plugin " + "b".repeat(230) + " x"
        assertTrue(ConfigSanitizer.sanitize(longPlugin).lines().all { it.toByteArray().size <= 250 })
    }

    @Test
    fun parseLineMatchesOpenVpn() {
        assertEquals(listOf("remote", "a b", "1194"), ConfigSanitizer.parseLine("remote \"a b\" 1194"))
        assertEquals(listOf("x", "c:\\k"), ConfigSanitizer.parseLine("x 'c:\\k'"))
        assertEquals(listOf("verb", "3"), ConfigSanitizer.parseLine("  verb 3 # comment"))
        assertEquals(emptyList<String>(), ConfigSanitizer.parseLine("; only a comment"))
        assertNull(ConfigSanitizer.parseLine("remote \"unclosed"))
        assertNull(ConfigSanitizer.parseLine("bad \\q escape"))
    }

    @Test
    fun managementQuoteStripsLineBreaks() {
        assertEquals("\"pa\\\"ss\"", ManagementClient.quote("pa\"ss"))
        assertEquals("\"abc\"", ManagementClient.quote("a\nb\r\nc"))
    }

    /** WireGuard's documentation example keys; not real credentials. */
    private val wgConfig = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.0.0.2/32, fd00::2/128
        DNS = 10.0.0.1
        PostUp = iptables -A FORWARD -i wg0 -j ACCEPT
        Table = off

        [Peer]
        # example server
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.com:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun wireGuardProfilesAreRecognised() {
        assertTrue(WireGuardProfile.looksLikeWireGuard(wgConfig))
        assertFalse(WireGuardProfile.looksLikeWireGuard("client\nremote x 1194\n"))
        val info = ConfigInfo.parse(wgConfig)
        assertEquals(ProfileKind.WireGuard, info.kind)
        assertEquals("vpn.example.com", info.remoteHost)
        assertEquals("51820", info.remotePort)
        assertFalse(info.needsCredentials)
        assertNull(info.unsupportedReason)
        assertEquals(ProfileKind.OpenVpn, ConfigInfo.parse("remote x 1194\n").kind)
    }

    @Test
    fun wireGuardCleanDropsShellCommandsAndUnknownKeys() {
        val cleaned = WireGuardProfile.clean(wgConfig + "\n[Interface]\nPreDown = rm -rf /\nIncludedApplications = com.example\n")
        assertFalse(cleaned.text.contains("PostUp") || cleaned.text.contains("PreDown") || cleaned.text.contains("iptables"))
        assertFalse(cleaned.text.contains("Table") || cleaned.text.contains("IncludedApplications"))
        assertTrue(cleaned.text.contains("PersistentKeepalive = 25"))
        assertEquals(listOf("PostUp", "Table", "PreDown", "IncludedApplications"), cleaned.dropped)
    }

    @Test
    fun wireGuardParseUsesTheLibraryParser() {
        val config = WireGuardProfile.parse(wgConfig)
        assertEquals(1, config.peers.size)
        assertEquals(2, config.peers[0].allowedIps.size)
        assertEquals(2, config.`interface`.addresses.size)
        val uapi = config.toWgUserspaceString()
        assertTrue(uapi.contains("private_key=") && uapi.contains("persistent_keepalive_interval=25"))
        assertFalse(uapi.contains("PostUp"))
    }

    @Test
    fun wireGuardBadProfilesGiveReadableErrors() {
        val badKey = wgConfig.replace("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "not-a-key")
        val e = runCatching { WireGuardProfile.parse(badKey) }.exceptionOrNull()
        assertTrue(e is InvalidProfileException && e.message!!.startsWith("The WireGuard profile has an error"))
        val noEndpoint = wgConfig.lines().filterNot { it.startsWith("Endpoint") }.joinToString("\n")
        assertTrue(ConfigInfo.parse(noEndpoint).unsupportedReason!!.contains("no Endpoint"))
        assertEquals("[fd00::1]".trim('[', ']') to "51820", WireGuardProfile.endpointOf("[Peer]\nEndpoint = [fd00::1]:51820\n"))
    }

    @Test
    fun wireGuardStatsSumPeersAndTakeTheNewestHandshake() {
        val uapi = "public_key=aa\nlast_handshake_time_sec=100\nrx_bytes=10\ntx_bytes=5\n" +
            "public_key=bb\nlast_handshake_time_sec=250\nrx_bytes=7\ntx_bytes=1\nerrno=0\n"
        assertEquals(WireGuardStats(250, 17, 6), WireGuardStats.parse(uapi))
        assertEquals(WireGuardStats(0, 0, 0), WireGuardStats.parse(""))
    }

    @Test
    fun profileEngineLabels() {
        val base = Profile("id", "n", "h", "1", "udp", false)
        assertEquals("OpenVPN UDP", base.engineLabel)
        assertEquals("WireGuard", base.copy(kind = ProfileKind.WireGuard).engineLabel)
    }

    @Test
    fun configInfoParsesRemoteAndRejectsTap() {
        val info = ConfigInfo.parse("client\nproto tcp\nremote 1.2.3.4 443\nauth-user-pass\n")
        assertEquals("1.2.3.4", info.remoteHost)
        assertEquals("443", info.remotePort)
        assertEquals("tcp", info.protocol)
        assertTrue(info.needsCredentials)
        assertNull(info.unsupportedReason)

        assertTrue(ConfigInfo.parse("dev tap\nremote x 1194\n").unsupportedReason != null)
        assertTrue(ConfigInfo.parse("just some text\n").unsupportedReason != null)
    }
}
