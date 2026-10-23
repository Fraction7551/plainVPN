package dev.ovpntv.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.wireguard.config.Config
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ovpntv.BuildConfig
import dev.ovpntv.OvpnTvApp
import dev.ovpntv.R
import dev.ovpntv.data.InvalidProfileException
import dev.ovpntv.data.Profile
import dev.ovpntv.data.ProfileKind
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.data.Settings
import dev.ovpntv.data.SplitMode
import dev.ovpntv.data.SplitTunnel
import dev.ovpntv.schedule.ReconnectScheduler
import dev.ovpntv.ui.MainActivity
import dev.ovpntv.update.Updater
import java.io.File
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address
import java.text.DateFormat
import java.util.Date

/**
 * plainVPN's VpnService. It owns the tunnel plumbing (placeholder block, split tunnel,
 * retries, schedule, notification) and runs each connection on an engine: OpenVPN as a
 * child process driven over its management interface, or WireGuard (wireguard-go) in
 * process. The class keeps its original name: Android's always-on VPN setting refers to it.
 */
class OpenVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var sessionCounter = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val resumeRunnable = Runnable { resumeAfterPause() }
    private var pauseWakeLock: PowerManager.WakeLock? = null
    /** Automatic restarts since the tunnel was last up; reset on CONNECTED or a user connect. */
    private var crashRestarts = 0
    private var restartPending = false
    private val restartRunnable = Runnable { connect(null, isRestart = true) }
    /** Placeholder tunnel that routes VPN apps nowhere while the real one is down. */
    private var blockTun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must promote to foreground promptly (startForegroundService contract).
        goForeground()
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_SCHEDULED_CYCLE -> scheduledCycle()
            ACTION_RESUME -> resumeAfterPause()
            ACTION_RESTORE_BLOCK -> restoreBlock()
            // Sticky restart after the app process died, or the crash-restore alarm: only come
            // back if the tunnel was wanted, and only once if both paths fire.
            null, ACTION_RESTORE -> if (session != null || blockTun != null) {
                VpnState.log("VPN already restored")
            } else if (Settings.wantConnected) {
                VpnState.log("App was restarted by the system, restoring the VPN")
                connect(null)
            } else if (Settings.keptBlockedError != null) {
                restoreBlock()
            } else {
                finish(TunnelState.Disconnected())
            }
            // ACTION_CONNECT and always-on VPN (SERVICE_INTERFACE).
            else -> connect(intent.getStringExtra(EXTRA_PROFILE_ID))
        }
        return START_STICKY
    }

    override fun onRevoke() {
        VpnState.log("VPN permission was revoked (another VPN app may have started)")
        disconnect(error = "VPN permission revoked by the system or another VPN app")
    }

    override fun onDestroy() {
        main.removeCallbacks(resumeRunnable)
        main.removeCallbacks(restartRunnable)
        releasePauseWakeLock()
        unregisterNetworkCallback()
        stopBlocking()
        session?.let { stopSession(it) {} }
        session = null
        if (VpnState.state.value !is TunnelState.Disconnected) VpnState.set(TunnelState.Disconnected())
        super.onDestroy()
    }

    // ---- Commands -------------------------------------------------------------

    private fun connect(profileId: String?, isRestart: Boolean = false) {
        main.removeCallbacks(resumeRunnable)
        main.removeCallbacks(restartRunnable)
        restartPending = false
        if (!isRestart) crashRestarts = 0
        ReconnectScheduler.cancelResume(this)
        releasePauseWakeLock()
        Settings.keptBlockedError = null

        val profile = ProfileRepository.get(profileId ?: Settings.selectedProfileId.value)
        when {
            profile == null -> return failAndStop("No VPN profile selected. Import one under Profiles.")
            profile.needsCredentials && !profile.hasCredentials ->
                return failAndStop("\"${profile.name}\" needs a username and password. Add them under Profiles.")
            prepare(this) != null ->
                return failAndStop("VPN permission not granted. Open the app and connect once to allow it.")
        }
        profile!!

        // Block before the old tunnel goes away so there is no gap for VPN apps.
        startBlocking(profile.name)
        session?.let { old -> stopSession(old) {} }
        Settings.wantConnected = true
        Settings.setSelectedProfile(profile.id)
        VpnState.setTraffic(Traffic())
        VpnState.set(TunnelState.Connecting("Starting", profile.name))
        updateNotification()
        registerNetworkCallback()

        val s = newSession(profile)
        session = s
        Thread({ s.start() }, "vpn-start").start()
    }

    private fun disconnect(error: String? = null) {
        main.removeCallbacks(resumeRunnable)
        main.removeCallbacks(restartRunnable)
        restartPending = false
        ReconnectScheduler.cancelResume(this)
        releasePauseWakeLock()
        Settings.wantConnected = false
        Settings.keptBlockedError = null
        unregisterNetworkCallback()
        val s = session
        session = null
        if (s == null) {
            finish(TunnelState.Disconnected(error))
            return
        }
        VpnState.set(TunnelState.Disconnecting)
        updateNotification()
        stopSession(s) { if (session == null) finish(TunnelState.Disconnected(error)) }
    }

    /** Fired by the reconnect schedule: take the tunnel down, bring it back after the offline period. */
    private fun scheduledCycle() {
        val schedule = Settings.schedule.value
        val running = session
        val profile = running?.profile ?: ProfileRepository.get(Settings.selectedProfileId.value)

        if (running == null) {
            if ((schedule.connectIfDisconnected || Settings.wantConnected) && profile != null) {
                VpnState.log("Scheduled reconnect: VPN was not running, connecting")
                connect(profile.id)
            } else if (VpnState.state.value !is TunnelState.ScheduledPause) {
                finish(TunnelState.Disconnected())
            }
            return
        }

        val offlineMillis = schedule.offlineSeconds * 1000L
        val resumeAt = System.currentTimeMillis() + offlineMillis
        VpnState.log("Scheduled reconnect: disconnecting for ${schedule.offlineSeconds}s")
        session = null
        VpnState.set(TunnelState.ScheduledPause(running.profile.name, resumeAt))
        updateNotification()
        startBlocking(running.profile.name)
        stopSession(running) {}

        // The alarm survives process death; the handler is the fast path for short pauses,
        // kept running by a wake lock so the box can't doze through it.
        ReconnectScheduler.scheduleResume(this, resumeAt)
        releasePauseWakeLock()
        pauseWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plainvpn:scheduled-reconnect")
            .apply { acquire(offlineMillis + 60_000) }
        main.postDelayed(resumeRunnable, offlineMillis)
    }

    private fun releasePauseWakeLock() {
        pauseWakeLock?.let { if (it.isHeld) it.release() }
        pauseWakeLock = null
    }

    private fun resumeAfterPause() {
        if (VpnState.state.value !is TunnelState.ScheduledPause && session != null) return
        if (VpnState.state.value is TunnelState.ScheduledPause || Settings.wantConnected) {
            VpnState.log("Scheduled reconnect: reconnecting")
            connect(null)
        } else {
            finish(TunnelState.Disconnected())
        }
    }

    /** After a reboot, app update or process restart: put back a block that was kept for an error. */
    private fun restoreBlock() {
        if (session != null || blockTun != null) return
        val error = Settings.keptBlockedError ?: return finish(TunnelState.Disconnected())
        VpnState.log("Keeping VPN apps blocked after the last error")
        startBlocking(ProfileRepository.get(Settings.selectedProfileId.value)?.name ?: getString(R.string.app_name))
        finish(TunnelState.Disconnected(error.ifEmpty { null }), keepBlocking = true)
    }

    private fun failAndStop(message: String) {
        VpnState.log(message)
        session?.let { stopSession(it) {} }
        session = null
        unregisterNetworkCallback()
        Settings.wantConnected = false
        finish(TunnelState.Disconnected(message), keepBlocking = true)
    }

    /**
     * Ends the attempt. With [keepBlocking] (errors a person has to fix) an already-running
     * block stays up: VPN apps stay offline until the user reconnects or taps Unblock,
     * and the service stays in the foreground to hold it.
     */
    private fun finish(state: TunnelState, keepBlocking: Boolean = false) {
        if (!keepBlocking) stopBlocking()
        VpnState.set(state)
        if (blockTun != null) {
            Settings.keptBlockedError = (state as? TunnelState.Disconnected)?.error.orEmpty()
            updateNotification()
            return
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Session handling ---------------------------------------------------

    private fun newSession(profile: Profile): Session = when (profile.kind) {
        ProfileKind.OpenVpn -> OpenVpnSession(profile, ++sessionCounter)
        ProfileKind.WireGuard -> WireGuardSession(profile)
    }

    private fun stopSession(s: Session, then: () -> Unit) {
        s.stopping = true
        s.requestStop()
        Thread({
            val deadline = System.currentTimeMillis() + 4000
            while (s.isAlive() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            if (s.isAlive()) {
                VpnState.log("${s.engineName} did not stop in time, killing it")
                s.kill()
            }
            s.release()
            main.post(then)
        }, "vpn-stop").start()
    }

    /** The engine of [s] stopped by itself ([exitCode] is engine-specific, for the log). */
    private fun onSessionEnded(s: Session, exitCode: Int) {
        s.release()
        if (session !== s || s.stopping) return
        session = null

        // Keep retrying anything that might fix itself (crashes, dropped servers, no internet);
        // only errors a person has to fix (password, profile, permission) end the attempt.
        if (!s.needsUser) {
            val delaySeconds = RETRY_DELAYS_SECONDS[minOf(crashRestarts, RETRY_DELAYS_SECONDS.lastIndex)]
            crashRestarts++
            val delay = if (delaySeconds < 60) "${delaySeconds}s" else "${delaySeconds / 60} min"
            val why = s.lastError?.let { ": $it" } ?: " (exit code $exitCode)"
            VpnState.log("${s.engineName} stopped unexpectedly$why. Retry $crashRestarts in $delay")
            startBlocking(s.profile.name)
            VpnState.set(TunnelState.Connecting("VPN dropped, retrying in $delay", s.profile.name))
            updateNotification()
            restartPending = true
            main.postDelayed(restartRunnable, delaySeconds * 1000)
            return
        }

        Settings.wantConnected = false
        unregisterNetworkCallback()
        val reason = s.lastError ?: "${s.engineName} could not start (exit code $exitCode). See Logs."
        VpnState.log(reason)
        finish(TunnelState.Disconnected(reason), keepBlocking = true)
    }

    /**
     * Opens the real tunnel for [s]: [configure] adds the engine's addresses, routes and DNS;
     * the split tunnel is applied here, and the placeholder block goes once the tunnel is up.
     */
    private fun establishTunnel(s: Session, configure: (Builder) -> Unit): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(s.profile.name)
            .setConfigureIntent(mainActivityIntent())
        val split = Settings.splitTunnel.value
        val tunFd = try {
            configure(builder)
            applySplitTunnel(builder, split)
            builder.establish()
        } catch (e: Exception) {
            VpnState.log("Could not create the VPN interface: ${e.message}")
            null
        } ?: return null
        // The real tunnel has replaced the placeholder, so it can go.
        main.post { if (session === s && !s.stopping) stopBlocking() }
        VpnState.setAppliedSplit(split)
        return tunFd
    }

    /**
     * One connection attempt with one VPN engine. The service owns the tunnel plumbing
     * (blocking, split tunnel, retries, notification); subclasses only drive their engine.
     */
    private abstract inner class Session(val profile: Profile) {
        @Volatile var stopping = false
        @Volatile var lastError: String? = null
        /** [lastError] needs the user to act (credentials, profile, permission), so retrying is pointless. */
        @Volatile var needsUser = false

        /** Shown in log lines and errors, e.g. "OpenVPN". */
        abstract val engineName: String

        /** Runs on a worker thread; reports progress with [publish] and failures with [ended]. */
        abstract fun start()

        /** Starts an orderly shutdown; [isAlive] turns false once it is done. */
        abstract fun requestStop()
        abstract fun isAlive(): Boolean

        /** Ends the engine at once, after [requestStop] timed out. */
        abstract fun kill()

        /** Frees what is left after the engine stopped; may be called more than once. */
        open fun release() {}

        open fun onNetworkChange() {}

        protected fun failForUser(message: String) {
            lastError = message
            needsUser = true
            VpnState.log(message)
        }

        protected fun current() = session === this && !stopping

        /** Tells the service the engine stopped by itself; it decides whether to retry. */
        protected fun ended(exitCode: Int) {
            main.post { onSessionEnded(this, exitCode) }
        }

        /** Shows a new state for this attempt, if it is still the current one. */
        protected fun publish(newState: TunnelState) {
            main.post {
                if (!current()) return@post
                if (newState is TunnelState.Connected) {
                    crashRestarts = 0
                    // The tunnel comes up at least daily (scheduled reconnect), so this is the
                    // background check for people who rarely open the app.
                    Updater.checkIfDue(this@OpenVpnService, maxAgeMillis = 20 * 60 * 60 * 1000L, notify = true)
                }
                VpnState.set(newState)
                updateNotification()
            }
        }
    }

    /** Runs the OpenVPN binary as a child process and answers its management interface. */
    private inner class OpenVpnSession(profile: Profile, id: Int) : Session(profile) {
        private val dir = File(cacheDir, "run").apply { mkdirs() }
        private val socketFile = File(dir, "mgmt-$id.sock")
        private val configFile = File(dir, "profile.ovpn")
        @Volatile private var process: Process? = null
        @Volatile private var management: ManagementClient? = null
        private var tun = TunConfig()

        override val engineName = "OpenVPN"

        override fun requestStop() {
            management?.send("signal SIGTERM")
        }

        override fun kill() {
            process?.destroy()
        }

        override fun release() {
            management?.close()
        }

        override fun onNetworkChange() {
            VpnState.log("Network changed, notifying OpenVPN")
            management?.send("network-change")
        }

        override fun isAlive(): Boolean {
            val p = process ?: return false
            return try {
                p.exitValue(); false
            } catch (e: IllegalThreadStateException) {
                true
            }
        }

        override fun start() {
            try {
                dir.listFiles()?.filter { it.name.startsWith("mgmt-") }?.forEach { it.delete() }
                configFile.writeText(ConfigSanitizer.sanitize(ProfileRepository.configFile(profile).readText()))
                val exe = File(applicationInfo.nativeLibraryDir, "libovpnexec.so")
                val command = listOf(
                    exe.absolutePath,
                    "--config", configFile.absolutePath,
                    // Options after --config override the profile's, so no profile can run programs.
                    "--script-security", "0",
                    "--management", socketFile.absolutePath, "unix",
                    "--management-hold",
                    "--management-query-passwords",
                    "--persist-tun",
                    "--setenv", "IV_GUI_VER", "plainVPN ${BuildConfig.VERSION_NAME}",
                )
                VpnState.log("Starting OpenVPN for \"${profile.name}\" (${profile.serverLabel} ${profile.protocol})")
                val p = ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .apply { environment()["TMPDIR"] = dir.absolutePath }
                    .start()
                process = p
                val logThread = Thread({
                    p.inputStream.bufferedReader().forEachLine {
                        VpnState.log(it)
                        // A bad profile fails the same way every time.
                        if (it.contains("Options error:")) {
                            lastError = "The profile has an error: ${optionsError(it)}"
                            needsUser = true
                        }
                    }
                }, "ovpn-log").apply { start() }
                Thread({
                    val code = p.waitFor()
                    // Let the last output lines (an Options error, say) land before deciding to retry.
                    logThread.join(2000)
                    ended(code)
                }, "ovpn-wait").start()

                if (stopping) return
                val client = ManagementClient(socketFile, ::handleLine) {}
                management = client
                client.connect(15_000, ::isAlive)
                client.send("state on")
                client.send("bytecount 2")
                client.send("hold release")
            } catch (e: Exception) {
                if (process == null) {
                    failForUser("Could not start OpenVPN: ${e.message}")
                    ended(-1)
                } else {
                    // OpenVPN exited early (its output says why) or never opened the socket;
                    // onSessionEnded decides from that whether to retry.
                    if (isAlive()) VpnState.log("${e.message}, restarting OpenVPN")
                    process?.destroy()
                }
            }
        }

        private fun handleLine(line: String) {
            when {
                line.startsWith(">HOLD:") -> management?.send("hold release")
                line.startsWith(">STATE:") -> onState(line.removePrefix(">STATE:").split(","))
                line.startsWith(">BYTECOUNT:") -> {
                    val p = line.removePrefix(">BYTECOUNT:").split(",")
                    VpnState.setTraffic(Traffic(p[0].toLongOrNull() ?: 0, p.getOrNull(1)?.toLongOrNull() ?: 0))
                }
                line.startsWith(">PASSWORD:") -> onPassword(line.removePrefix(">PASSWORD:"))
                line.startsWith(">NEED-OK:") -> onNeedOk(line)
                line.startsWith(">FATAL:") -> {
                    lastError = line.removePrefix(">FATAL:")
                    VpnState.log("Fatal: $lastError")
                }
                line.startsWith("ERROR:") -> VpnState.log("Management: $line")
            }
        }

        private fun onState(f: List<String>) {
            val name = f.getOrNull(1) ?: return
            if (!current()) return
            val newState = when (name) {
                "CONNECTED" -> TunnelState.Connected(
                    profileName = profile.name,
                    localIp = f.getOrNull(3)?.takeIf { it.isNotEmpty() },
                    serverIp = f.getOrNull(4)?.takeIf { it.isNotEmpty() },
                    sinceMillis = System.currentTimeMillis(),
                )
                "EXITING" -> return
                else -> TunnelState.Connecting(stageLabel(name), profile.name)
            }
            publish(newState)
        }

        private fun onPassword(msg: String) {
            when {
                msg.startsWith("Verification Failed") ->
                    failForUser("Authentication failed. Check the username and password for \"${profile.name}\".")
                msg.startsWith("Need 'Auth'") -> {
                    val fresh = ProfileRepository.get(profile.id) ?: profile
                    if (!fresh.hasCredentials) {
                        failForUser("\"${profile.name}\" needs a username and password.")
                        management?.send("signal SIGTERM")
                        return
                    }
                    management?.send("username \"Auth\" ${ManagementClient.quote(fresh.username)}")
                    management?.send("password \"Auth\" ${ManagementClient.quote(fresh.password)}")
                }
                msg.startsWith("Need 'Private Key'") -> {
                    failForUser("This profile uses a password-protected private key, which is not supported yet.")
                    management?.send("signal SIGTERM")
                }
                msg.startsWith("Need 'HTTP Proxy'") -> {
                    failForUser("This profile's HTTP proxy needs credentials, which is not supported yet.")
                    management?.send("signal SIGTERM")
                }
                msg.startsWith("Auth-Token") -> Unit
                else -> VpnState.log("Unhandled password request: $msg")
            }
        }

        private fun onNeedOk(line: String) {
            val match = NEED_OK.find(line) ?: return VpnState.log("Unparsed request: $line")
            val (command, msg) = match.destructured
            val client = management ?: return
            when (command) {
                "PROTECTFD" -> {
                    val fd = client.takeReceivedFd()
                    if (fd == null) VpnState.log("PROTECTFD request arrived without a socket")
                    else protectFd(fd)
                    client.send("needok 'PROTECTFD' ok")
                    return
                }
                "IFCONFIG" -> tun.ifconfig(msg)
                "IFCONFIG6" -> tun.ifconfig6(msg)
                "ROUTE" -> tun.route(msg)
                "ROUTE6" -> tun.route6(msg)
                "DNSSERVER", "DNS6SERVER" -> tun.dns(msg)
                "DNSDOMAIN" -> tun.domain(msg)
                "HTTPPROXY" -> tun.httpProxy(msg)
                "PERSIST_TUN_ACTION" -> {
                    client.send("needok 'PERSIST_TUN_ACTION' OPEN_BEFORE_CLOSE")
                    return
                }
                "OPENTUN" -> {
                    openTun(client)
                    return
                }
                else -> VpnState.log("Unknown request '$command', accepting")
            }
            client.send("needok '$command' ok")
        }

        private fun protectFd(fd: FileDescriptor) {
            try {
                ParcelFileDescriptor.dup(fd).use { pfd ->
                    if (!protect(pfd.fd)) VpnState.log("protect() failed for OpenVPN socket")
                }
            } finally {
                runCatching { Os.close(fd) }
            }
        }

        private fun openTun(client: ManagementClient) {
            val config = tun
            tun = TunConfig()
            val tunFd = establishTunnel(this) { config.apply(it) { line -> VpnState.log(line) } }
            if (tunFd == null) {
                failForUser("Android refused to create the VPN interface. Is VPN permission granted?")
                client.send("needok 'OPENTUN' cancel")
                return
            }
            // OpenVPN receives its own copy of the fd; ours is closed right after sending.
            tunFd.use { client.sendWithFd("needok 'OPENTUN' ok", it.fileDescriptor) }
        }
    }

    /**
     * Runs a WireGuard profile on wireguard-go, inside this process. The service opens the
     * tunnel (so blocking and split tunnelling work as with OpenVPN) and hands its fd over.
     * WireGuard has no "connected" event: the session counts as connected after the first
     * handshake with the server, which it checks every second or two.
     */
    private inner class WireGuardSession(profile: Profile) : Session(profile) {
        private val lock = Any()
        @Volatile private var handle = -1
        @Volatile private var watcher: Thread? = null

        override val engineName = "WireGuard"

        override fun start() {
            try {
                val text = ProfileRepository.configFile(profile).readText()
                WireGuardProfile.clean(text).dropped.forEach { VpnState.log("WireGuard profile: ignoring $it") }
                val config = WireGuardProfile.parse(text)
                VpnState.log("Starting WireGuard for \"${profile.name}\" (${profile.serverLabel})")
                publish(TunnelState.Connecting("Resolving server", profile.name))
                val server = resolveEndpoints(config) ?: return ended(-2)
                val settings = config.toWgUserspaceString()
                if (stopping) return

                val tunFd = establishTunnel(this) { configure(it, config) }
                if (tunFd == null) {
                    failForUser("Android refused to create the VPN interface. Is VPN permission granted?")
                    return ended(-1)
                }
                val h = synchronized(lock) {
                    if (stopping) {
                        runCatching { tunFd.close() }
                        return
                    }
                    WireGuardEngine.turnOn(profile.name, tunFd.detachFd(), settings).also { handle = it }
                }
                if (h < 0) {
                    handle = -1
                    failForUser("WireGuard couldn't start the tunnel (error $h). See Logs.")
                    return ended(h)
                }
                // Its own UDP sockets must not loop back into the tunnel.
                listOf(WireGuardEngine.socketV4(h), WireGuardEngine.socketV6(h)).filter { it >= 0 }.forEach {
                    if (!protect(it)) VpnState.log("protect() failed for a WireGuard socket")
                }
                VpnState.log("WireGuard ${WireGuardEngine.version().orEmpty()} is running, waiting for the server's handshake".replace("  ", " "))
                publish(TunnelState.Connecting("Waiting for the server", profile.name))
                watcher = Thread({ watch(h, config, server) }, "wg-watch").apply { start() }
            } catch (e: InvalidProfileException) {
                failForUser(e.message.orEmpty())
                ended(-1)
            } catch (e: Throwable) {
                lastError = "WireGuard failed to start: ${e.message ?: e.javaClass.simpleName}"
                VpnState.log(lastError!!)
                ended(-3)
            }
        }

        /**
         * Resolves every peer's Endpoint before the tunnel opens (our own app is outside the
         * placeholder block, so this uses the normal network). Null when it can't: no internet
         * or DNS yet, which the service retries with its usual backoff.
         */
        private fun resolveEndpoints(config: Config): String? {
            var first: String? = null
            for (peer in config.peers) {
                val endpoint = peer.endpoint.orElse(null) ?: continue
                var resolved: com.wireguard.config.InetEndpoint? = null
                for (attempt in 1..5) {
                    if (stopping) return null
                    resolved = endpoint.resolved.orElse(null)
                    if (resolved != null) break
                    Thread.sleep(1000)
                }
                if (resolved == null) {
                    lastError = "Couldn't find the WireGuard server ${endpoint.host}. Is the internet working?"
                    VpnState.log(lastError!!)
                    return null
                }
                if (first == null) first = resolved.host
            }
            return first ?: ""
        }

        /** Addresses, routes (AllowedIPs), DNS and MTU from the profile. */
        private fun configure(builder: Builder, config: Config) {
            val iface = config.`interface`
            builder.setMtu(iface.mtu.orElse(1280))
            iface.addresses.forEach { builder.addAddress(it.address, it.mask) }
            iface.dnsServers.forEach { runCatching { builder.addDnsServer(it) }.onFailure { e -> VpnState.log("Bad DNS server $it: ${e.message}") } }
            iface.dnsSearchDomains.forEach { builder.addSearchDomain(it) }
            val routes = config.peers.flatMap { it.allowedIps }
            routes.forEach { r ->
                runCatching { builder.addRoute(TunConfig.networkAddress(r.address.hostAddress.orEmpty(), r.mask), r.mask) }
                    .onFailure { VpnState.log("Could not add route $r: ${it.message}") }
            }
            // Same leak rule as OpenVPN (TunConfig): if all IPv4 goes through the tunnel and the
            // profile has no IPv6, Android blocks IPv6 instead of letting it out unprotected.
            val v4 = routes.filter { it.address is Inet4Address }
            val redirectsAll4 = v4.any { it.mask == 0 } ||
                (v4.any { it.mask == 1 && it.address.hostAddress == "0.0.0.0" } && v4.any { it.mask == 1 && it.address.hostAddress == "128.0.0.0" })
            if (!redirectsAll4) {
                if (iface.addresses.none { it.address is Inet4Address }) builder.allowFamily(OsConstants.AF_INET)
                if (iface.addresses.none { it.address is Inet6Address } && routes.none { it.address is Inet6Address }) {
                    builder.allowFamily(OsConstants.AF_INET6)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            VpnState.log(
                "Tunnel: ip ${iface.addresses.joinToString()}, mtu ${iface.mtu.orElse(1280)}, routes ${routes.joinToString()}" +
                    if (iface.dnsServers.isEmpty()) "" else ", dns ${iface.dnsServers.joinToString { it.hostAddress.orEmpty() }}",
            )
        }

        /** Reports Connected after the first handshake, and the traffic counters, until the tunnel goes. */
        private fun watch(h: Int, config: Config, server: String) {
            val started = System.currentTimeMillis()
            var connected = false
            var warned = false
            val localIp = config.`interface`.addresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
            while (handle == h && !stopping) {
                val stats = runCatching { WireGuardEngine.stats(h) }.getOrNull()
                if (stats != null && current()) {
                    VpnState.setTraffic(Traffic(stats.rxBytes, stats.txBytes))
                    if (!connected && stats.lastHandshakeEpochSeconds > 0) {
                        connected = true
                        VpnState.log("WireGuard handshake with the server done")
                        publish(TunnelState.Connected(profile.name, localIp, server.ifEmpty { null }, System.currentTimeMillis()))
                    }
                }
                if (!connected && !warned && System.currentTimeMillis() - started > 30_000) {
                    warned = true
                    VpnState.log("No answer from the WireGuard server yet. Check the profile's keys and Endpoint; still trying.")
                }
                try {
                    Thread.sleep(if (connected) 2000 else 1000)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }

        override fun requestStop() {
            synchronized(lock) {
                val h = handle
                handle = -1
                if (h >= 0) runCatching { WireGuardEngine.turnOff(h) }.onFailure { VpnState.log("WireGuard stop failed: ${it.message}") }
            }
            watcher?.interrupt()
        }

        override fun isAlive() = handle >= 0

        override fun kill() = requestStop()

        override fun onNetworkChange() {
            // wireguard-go roams to the new network by itself; nothing to restart.
            VpnState.log("Network changed (WireGuard keeps going)")
        }
    }

    private fun installedApps(split: SplitTunnel) = split.apps.filter { pkg ->
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /**
     * Opens a tunnel for the same apps as the real one that leads nowhere: their traffic is
     * queued on an fd nobody reads, so they stay offline instead of using the plain network.
     * Our own app is always left out so OpenVPN can still resolve and reach the server.
     * Android may keep this tunnel's network for the real one, session name included,
     * so it carries the profile's name ([sessionName]) from the start.
     */
    private fun startBlocking(sessionName: String) {
        if (blockTun != null || !Settings.blockWhileReconnecting.value) return
        val split = Settings.splitTunnel.value
        val installed = installedApps(split)
        val builder = Builder()
            .setSession(sessionName)
            .setConfigureIntent(mainActivityIntent())
            .addAddress(BLOCK_ADDRESS4, 32)
            .addAddress(BLOCK_ADDRESS6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(BLOCK_DNS)
        if (split.isActive && split.mode == SplitMode.OnlySelected) {
            // With none of them installed no app uses the VPN, so there is nothing to protect.
            if (installed.isEmpty()) return
            installed.forEach { builder.addAllowedApplication(it) }
        } else {
            builder.addDisallowedApplication(packageName)
            if (split.isActive) installed.forEach { builder.addDisallowedApplication(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        blockTun = runCatching { builder.establish() }
            .onFailure { VpnState.log("Could not block VPN apps: ${it.message}") }
            .getOrNull()
        if (blockTun != null) {
            VpnState.log("Blocking VPN apps until the tunnel is up")
            VpnState.setBlocking(true)
        }
    }

    private fun stopBlocking() {
        val fd = blockTun ?: return
        blockTun = null
        runCatching { fd.close() }
        VpnState.setBlocking(false)
    }

    private fun applySplitTunnel(builder: Builder, split: SplitTunnel) {
        if (!split.isActive) return
        val installed = installedApps(split)
        (split.apps - installed.toSet()).forEach { VpnState.log("Split tunneling: $it is not installed, skipping") }
        when (split.mode) {
            SplitMode.OnlySelected -> {
                installed.forEach { builder.addAllowedApplication(it) }
                if (installed.isEmpty()) {
                    // With no allowed apps Android would tunnel everything; keep the tunnel to ourselves instead.
                    builder.addAllowedApplication(packageName)
                    VpnState.log("Split tunneling: none of the selected apps are installed, so no app uses the VPN")
                } else {
                    VpnState.log("Split tunneling: only ${installed.joinToString()} use the VPN")
                }
            }
            SplitMode.ExceptSelected -> {
                installed.forEach { builder.addDisallowedApplication(it) }
                VpnState.log("Split tunneling: ${installed.joinToString()} bypass the VPN")
            }
            SplitMode.Off -> Unit
        }
    }

    // ---- Network changes ----------------------------------------------------

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val available = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOffline = available.isEmpty()
                available += network
                if (wasOffline) notifyNetworkChange()
            }

            override fun onLost(network: Network) {
                available -= network
                if (available.isNotEmpty()) notifyNetworkChange()
            }
        }
        // The default NetworkRequest excludes VPNs, so our own tunnel is never reported.
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun notifyNetworkChange() {
        // Callbacks arrive on a ConnectivityManager thread; the retry state lives on main.
        main.post {
            if (restartPending && session == null) {
                VpnState.log("Network changed, retrying now")
                connect(null, isRestart = true)
            }
        }
        val s = session ?: return
        if (VpnState.state.value is TunnelState.Connected || VpnState.state.value is TunnelState.Connecting) {
            s.onNetworkChange()
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    // ---- Notification -------------------------------------------------------

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        if (session == null && blockTun == null && VpnState.state.value !is TunnelState.ScheduledPause &&
            VpnState.state.value !is TunnelState.Disconnecting) return
        ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val (title, text) = when (val s = VpnState.state.value) {
            is TunnelState.Connected -> "Connected" to "${s.profileName}${s.localIp?.let { " · $it" } ?: ""}"
            is TunnelState.Connecting -> "Connecting" to "${s.profileName} · ${s.stage}"
            is TunnelState.ScheduledPause ->
                "Scheduled reconnect" to "Reconnecting at ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(s.resumeAtMillis))}"
            TunnelState.Disconnecting -> "Disconnecting" to ""
            is TunnelState.Disconnected ->
                if (blockTun != null) "VPN apps blocked" to (s.error ?: "Reconnect or unblock in the app")
                else "Disconnected" to ""
        }
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, OpenVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, OvpnTvApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainActivityIntent())
            .addAction(0, if (VpnState.state.value is TunnelState.Disconnected) "Unblock apps" else "Disconnect", disconnect)
            .build()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_CONNECT = "dev.ovpntv.CONNECT"
        const val ACTION_DISCONNECT = "dev.ovpntv.DISCONNECT"
        const val ACTION_SCHEDULED_CYCLE = "dev.ovpntv.SCHEDULED_CYCLE"
        const val ACTION_RESUME = "dev.ovpntv.RESUME"
        const val ACTION_RESTORE_BLOCK = "dev.ovpntv.RESTORE_BLOCK"
        const val ACTION_RESTORE = "dev.ovpntv.RESTORE"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val NOTIFICATION_ID = 1
        /** Waits between automatic restarts; the last one repeats until the tunnel is back. */
        private val RETRY_DELAYS_SECONDS = longArrayOf(5, 10, 20, 40, 60, 120)
        private const val BLOCK_ADDRESS4 = "10.255.255.1"
        private const val BLOCK_ADDRESS6 = "fd00:ffff::1"
        /** Inside the blocked routes, so DNS lookups go nowhere too. */
        private const val BLOCK_DNS = "10.255.255.2"
        private val NEED_OK = Regex(">NEED-OK:Need '([^']+)' confirmation MSG:(.*)")

        private val stageLabels = mapOf(
            "CONNECTING" to "Connecting",
            "RESOLVE" to "Resolving server",
            "TCP_CONNECT" to "Connecting (TCP)",
            "WAIT" to "Waiting for server",
            "AUTH" to "Authenticating",
            "AUTH_PENDING" to "Waiting for authentication",
            "GET_CONFIG" to "Getting configuration",
            "ASSIGN_IP" to "Assigning IP address",
            "ADD_ROUTES" to "Adding routes",
            "RECONNECTING" to "Reconnecting",
        )

        private fun stageLabel(state: String) = stageLabels[state] ?: state.lowercase().replaceFirstChar { it.uppercase() }

        /**
         * "Options error: Unrecognized option ... in /data/.../profile.ovpn:6: foo (2.7.8)" becomes
         * "Unrecognized option ...: foo", so the part that names the problem fits on Home.
         */
        private fun optionsError(line: String) = line.substringAfter("Options error:").trim()
            .replace(Regex(""" in \S+:\d+:"""), ":")
            .replace(Regex("""\s*\(\d+\.\d+[^)]*\)$"""), "")

        fun connect(context: Context, profileId: String? = null) = start(
            context, Intent(context, OpenVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_PROFILE_ID, profileId),
        )

        fun disconnect(context: Context) {
            if (!VpnState.isActive && !VpnState.blocking.value) return
            start(context, Intent(context, OpenVpnService::class.java).setAction(ACTION_DISCONNECT))
        }

        fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
