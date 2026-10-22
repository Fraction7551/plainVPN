package dev.ovpntv.vpn

/**
 * Rewrites an imported profile into one the embedded OpenVPN can run on Android.
 *
 * Directives that run programs, load plugins, write files or open a management port
 * are dropped. Credentials always go through the management interface, so
 * `auth-user-pass <file>` becomes a bare `auth-user-pass`. OpenVPN is also started with
 * `--script-security 0` after `--config`, so a script that slips through still can't run.
 *
 * Lines are read exactly the way OpenVPN 2.7 reads a config file (options_parse.c):
 * quotes and backslashes in the directive name, a leading "--", inline <blocks> that
 * end at the first line starting with the close tag, and 256-byte line chunks. Anything
 * that would read differently there is dropped rather than guessed at.
 */
object ConfigSanitizer {
    private val dropped = setOf(
        "config",
        "management", "management-client", "management-hold", "management-query-passwords",
        "management-signal", "management-forget-disconnect", "management-up-down",
        "management-client-auth", "management-external-key", "management-external-cert",
        "log", "log-append", "syslog", "daemon", "writepid", "status", "status-version",
        "replay-persist", "tls-export-cert", "ifconfig-pool-persist", "genkey",
        "user", "group", "chroot", "cd", "mlock",
        "up", "down", "route-up", "route-pre-down", "ipchange", "tls-verify", "learn-address",
        "client-connect", "client-disconnect", "auth-user-pass-verify", "dns-updown",
        "script-security", "plugin", "iproute",
        "dev-node", "service", "block-outside-dns", "register-dns", "ip-win32",
        "route-method", "tap-sleep", "windows-driver", "allow-pull-fqdn",
    )

    /**
     * OpenVPN reads config lines with fgets into a 256-byte buffer, so the rest of a longer
     * line is parsed as a line of its own. Lines near that size are dropped instead.
     */
    private const val MAX_LINE_BYTES = 250
    private const val MAX_PARMS = 16
    private val BOM = 0xFEFF.toChar().toString()

    enum class Kind { Blank, Directive, Inline, Rejected }

    /** One line of a profile as OpenVPN sees it; [tokens] is set for [Kind.Directive]. */
    class Line(val raw: String, val kind: Kind, val tokens: List<String> = emptyList())

    fun sanitize(config: String): String {
        val out = StringBuilder()
        for (line in lines(config)) {
            when (line.kind) {
                Kind.Blank, Kind.Inline -> out.appendLine(line.raw)
                Kind.Rejected -> out.appendLine("# [plainVPN removed a line OpenVPN would misread]")
                Kind.Directive -> {
                    val tokens = line.tokens
                    val directive = tokens[0]
                    when {
                        directive in dropped -> out.appendLine("# [plainVPN removed] ${printable(directive)}")
                        directive == "setenv" && tokens.getOrNull(2) == "block-outside-dns" ->
                            out.appendLine("# [plainVPN removed] setenv block-outside-dns")
                        directive == "auth-user-pass" && tokens.size > 1 -> {
                            out.appendLine("# [plainVPN: credentials come from the app]")
                            out.appendLine("auth-user-pass")
                        }
                        else -> out.appendLine(line.raw)
                    }
                }
            }
        }
        return out.toString()
    }

    /** Tokenised directive lines, skipping comments and inline <blocks>. */
    fun directives(config: String): Sequence<List<String>> =
        lines(config).filter { it.kind == Kind.Directive }.map { it.tokens }

    fun lines(config: String): Sequence<Line> = sequence {
        var closeTag: String? = null
        config.split('\n').forEachIndexed { index, raw ->
            // C string functions stop at a NUL byte; the UTF-8 BOM is skipped on the first line only.
            val text = raw.substringBefore('\u0000').let { if (index == 0) it.removePrefix(BOM) else it }
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) {
                yield(Line(raw, Kind.Rejected))
                return@forEachIndexed
            }
            val tag = closeTag
            if (tag != null) {
                if (text.trimStart(::isCSpace).startsWith(tag)) closeTag = null
                yield(Line(raw, Kind.Inline))
                return@forEachIndexed
            }
            val tokens = parseLine(text)
            when {
                tokens == null -> yield(Line(raw, Kind.Rejected))
                tokens.isEmpty() -> yield(Line(raw, Kind.Blank))
                else -> {
                    val first = tokens[0].let { if (it.length >= 3 && it.startsWith("--")) it.substring(2) else it }
                    val parts = listOf(first) + tokens.drop(1)
                    if (parts.size == 1 && first.length >= 2 && first.startsWith('<') && first.endsWith('>')) {
                        closeTag = "</${first.substring(1, first.length - 1)}>"
                        yield(Line(raw, Kind.Inline))
                    } else {
                        yield(Line(raw, Kind.Directive, parts))
                    }
                }
            }
        }
    }

    /** Port of OpenVPN's parse_line(): the line's tokens, or null when OpenVPN would reject it. */
    fun parseLine(line: String): List<String>? {
        val tokens = mutableListOf<String>()
        val parm = StringBuilder()
        var state = INITIAL
        var backslash = false
        // The C loop also processes the terminating NUL, which ends an unquoted token.
        for (c in line + '\u0000') {
            var out: Char? = null
            if (!backslash && c == '\\' && state != SQUOTED) {
                backslash = true
            } else {
                when (state) {
                    INITIAL -> if (!isSpace(c)) {
                        if (c == ';' || c == '#') break
                        when {
                            !backslash && c == '"' -> state = QUOTED
                            !backslash && c == '\'' -> state = SQUOTED
                            else -> { out = c; state = UNQUOTED }
                        }
                    }
                    UNQUOTED -> if (!backslash && isSpace(c)) state = DONE else out = c
                    QUOTED -> if (!backslash && c == '"') state = DONE else out = c
                    SQUOTED -> if (c == '\'') state = DONE else out = c
                }
                if (state == DONE) {
                    tokens += parm.toString()
                    parm.clear()
                    state = INITIAL
                }
                if (backslash && out != null && out != '\u0000') {
                    if (!(out == '\\' || out == '"' || isSpace(out))) return null
                }
                backslash = false
            }
            if (out != null && out != '\u0000') parm.append(out)
            if (tokens.size >= MAX_PARMS) break
        }
        return if (state == INITIAL) tokens else null
    }

    private const val INITIAL = 0
    private const val QUOTED = 1
    private const val UNQUOTED = 2
    private const val DONE = 3
    private const val SQUOTED = 4

    /** C isspace() in the "C" locale. */
    private fun isCSpace(c: Char) = c == ' ' || c in '\t'..'\r'

    /** OpenVPN's space(): NUL or isspace(). */
    private fun isSpace(c: Char) = c == '\u0000' || isCSpace(c)

    private fun printable(s: String) = s.filter { it in ' '..'~' }.take(40)
}
