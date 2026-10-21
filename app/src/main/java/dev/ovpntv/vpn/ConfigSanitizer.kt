package dev.ovpntv.vpn

/**
 * Rewrites an imported profile into one the embedded OpenVPN can run on Android.
 *
 * Android gives an unprivileged app no way to run scripts, change users, write
 * system logs or open a management port of its own choosing, so directives that
 * try are dropped. Credentials always go through the management interface, so
 * `auth-user-pass <file>` becomes a bare `auth-user-pass`.
 */
object ConfigSanitizer {
    private val dropped = setOf(
        "management", "management-client", "management-hold", "management-query-passwords",
        "management-signal", "management-forget-disconnect", "management-up-down",
        "management-client-auth", "management-external-key", "management-external-cert",
        "log", "log-append", "syslog", "daemon", "writepid", "status", "status-version",
        "user", "group", "chroot", "cd", "mlock",
        "up", "down", "route-up", "route-pre-down", "ipchange", "tls-verify", "learn-address",
        "client-connect", "client-disconnect", "auth-user-pass-verify", "dns-updown",
        "script-security", "plugin",
        "dev-node", "service", "block-outside-dns", "register-dns", "ip-win32",
        "route-method", "tap-sleep", "windows-driver", "allow-pull-fqdn",
    )

    fun sanitize(config: String): String {
        val out = StringBuilder()
        var inBlock: String? = null
        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (inBlock != null) {
                out.appendLine(raw)
                if (line == "</$inBlock>") inBlock = null
                continue
            }
            if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) {
                inBlock = line.substring(1, line.length - 1)
                out.appendLine(raw)
                continue
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                out.appendLine(raw)
                continue
            }
            val tokens = line.removePrefix("--").split(Regex("\\s+"))
            val directive = tokens[0]
            when {
                directive in dropped -> out.appendLine("# [ovpntv removed] $line")
                directive == "setenv" && tokens.getOrNull(2) == "block-outside-dns" ->
                    out.appendLine("# [ovpntv removed] $line")
                directive == "auth-user-pass" && tokens.size > 1 -> {
                    out.appendLine("# [ovpntv: credentials come from the app] $line")
                    out.appendLine("auth-user-pass")
                }
                else -> out.appendLine(raw)
            }
        }
        return out.toString()
    }
}
