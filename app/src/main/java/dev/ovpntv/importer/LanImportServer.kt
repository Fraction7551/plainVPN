package dev.ovpntv.importer

import dev.ovpntv.data.InvalidProfileException
import dev.ovpntv.data.ProfileRepository
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Semaphore

/**
 * Tiny HTTP server that lets a phone or PC on the same network upload .ovpn
 * files to the TV. It only runs while the import screen is open, and every
 * upload must carry the PIN shown on the TV. Connections that come in over a
 * VPN tunnel or from outside the local network are refused.
 */
class LanImportServer(private val onImported: (String) -> Unit, private val onError: (String) -> Unit) {
    val pin: String = (SecureRandom().nextInt(900_000) + 100_000).toString()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val pinLock = Any()
    private var failedPins = 0
    private val slots = Semaphore(MAX_CLIENTS)

    val port: Int get() = server?.localPort ?: 0

    fun start(): Boolean {
        val socket = (PORTS.firstNotNullOfOrNull { p ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) } }.getOrNull()
        }) ?: return false
        server = socket
        running = true
        Thread({
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (!isLocalNetwork(client) || !slots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                Thread({
                    try {
                        client.use { handle(it) }
                    } catch (e: Exception) {
                        // A dropped or timed-out connection; nothing to report.
                    } finally {
                        slots.release()
                    }
                }, "lan-import-client").start()
            }
        }, "lan-import").start()
        return true
    }

    /**
     * True for a connection from the local network: not through a VPN tunnel (the import
     * screen may be open while plainVPN is connected) and from a private address.
     */
    private fun isLocalNetwork(client: Socket): Boolean {
        val iface = runCatching { NetworkInterface.getByInetAddress(client.localAddress) }.getOrNull()
        if (iface == null || TUNNEL_PREFIXES.any { iface.name.startsWith(it) }) return false
        val remote = client.inetAddress
        return remote.isSiteLocalAddress || remote.isLinkLocalAddress || remote.isLoopbackAddress ||
            (remote is Inet6Address && (remote.address[0].toInt() and 0xfe) == 0xfc)
    }

    /** Counts a PIN attempt; checked and counted under one lock so parallel requests can't slip past the limit. */
    private fun checkPin(given: String?): PinResult = synchronized(pinLock) {
        when {
            failedPins >= MAX_FAILED_PINS -> PinResult.LockedOut
            given == pin -> PinResult.Ok
            else -> { failedPins++; PinResult.Wrong }
        }
    }

    private enum class PinResult { Ok, Wrong, LockedOut }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        // A whole request must arrive within this, so a slow sender can't hold a slot forever.
        val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input, deadline) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input, deadline) ?: return
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0)
        val path = parts.getOrNull(1).orEmpty()

        // The TV shows an IP address to open. A request for any other host name is a web page
        // that re-pointed its own domain at the TV (DNS rebinding), so it is refused.
        if (!isIpHost(headers["host"])) return respond(out, 403, "text/plain", "Open the address shown on the TV.")

        when {
            method == "GET" && (path == "/" || path.startsWith("/?")) -> respond(out, 200, "text/html; charset=utf-8", PAGE)
            method == "POST" && path == "/upload" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: -1
                if (length !in 1..MAX_UPLOAD) return respond(out, 413, "text/plain", "File is empty or larger than 1 MB")
                when (checkPin(headers["x-pin"])) {
                    PinResult.LockedOut -> return respond(out, 403, "text/plain", "Too many wrong PINs. Reopen the import screen on the TV.")
                    PinResult.Wrong -> return respond(out, 403, "text/plain", "Wrong PIN. Use the PIN shown on the TV.")
                    PinResult.Ok -> Unit
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    if (System.currentTimeMillis() > deadline) return
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val name = runCatching { URLDecoder.decode(headers["x-filename"] ?: "Profile", "UTF-8") }.getOrDefault("Profile").take(100)
                try {
                    val profile = ProfileRepository.import(name, String(body, 0, read, Charsets.UTF_8))
                    onImported(profile.name)
                    respond(out, 200, "text/plain", "Imported \"${profile.name}\"")
                } catch (e: InvalidProfileException) {
                    onError(e.message.orEmpty())
                    respond(out, 422, "text/plain", e.message.orEmpty())
                }
            }
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    private fun readLine(input: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            if (System.currentTimeMillis() > deadline) return null
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            if (buf.size() > 8192) return null
            buf.write(b)
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found", 413 to "Payload Too Large", 422 to "Unprocessable Entity")[code]
        // No caching, no sniffing, no framing by another site.
        val security = "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'\r\n"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n${security}Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        private val PORTS = listOf(8080, 8088, 8888, 0)
        private const val MAX_UPLOAD = 1024 * 1024
        private const val MAX_FAILED_PINS = 10
        private const val MAX_CLIENTS = 4
        private const val MAX_HEADERS = 64
        private const val REQUEST_DEADLINE_MS = 30_000L

        /** True for "192.168.1.50", "192.168.1.50:8080", "[fd00::1]:8080": an IP literal, not a name. */
        fun isIpHost(host: String?): Boolean {
            if (host.isNullOrEmpty()) return false
            if (host.startsWith("[")) return host.substringBefore(']').drop(1).let { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == ':' || c == '.' } }
            val ip = host.substringBefore(':')
            val parts = ip.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } }
        }
        private val TUNNEL_PREFIXES = listOf("tun", "ppp", "ipsec", "wg")

        /** Site-local IPv4 addresses of this device, for display. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress.orEmpty() }
        }.getOrDefault(emptyList())

        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Send profile to TV</title>
<style>
:root{--bg:#192E45;--card:#294D73;--fg:#F8F7F1;--muted:#A3ABB5;--green:#44AD4D;--red:#E34039}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif;display:flex;justify-content:center;padding:24px 16px}
main{max-width:440px;width:100%}h1{font-size:24px;margin:0 0 8px}p{color:var(--muted);margin:0 0 20px}
label{display:block;font-weight:600;margin:16px 0 6px}input{width:100%;box-sizing:border-box;padding:12px;border-radius:4px;border:0;background:var(--card);color:var(--fg);font-size:16px}
button{margin-top:24px;width:100%;padding:12px;border:0;border-radius:4px;background:var(--green);color:var(--fg);font-size:16px;font-weight:600}
#msg{margin-top:16px;font-weight:600}
</style></head><body><main>
<h1>Send VPN profiles to your TV</h1><p>Pick one or more OpenVPN (.ovpn) or WireGuard (.conf) files and enter the PIN shown on the TV screen.</p>
<label for="f">Profile files</label><input id="f" type="file" accept=".ovpn,.conf" multiple>
<label for="pin">PIN</label><input id="pin" inputmode="numeric" maxlength="6" autocomplete="off">
<button id="go">Send to TV</button><div id="msg"></div>
<script>
document.getElementById('go').onclick=async()=>{const m=document.getElementById('msg');const files=document.getElementById('f').files;const pin=document.getElementById('pin').value.trim();
if(!files.length){m.style.color='var(--red)';m.textContent='Choose a file first.';return}
let out=[];for(const file of files){try{const r=await fetch('/upload',{method:'POST',headers:{'X-Pin':pin,'X-Filename':encodeURIComponent(file.name),'Content-Type':'text/plain'},body:await file.text()});
out.push((r.ok?'✓ ':'✗ ')+await r.text())}catch(e){out.push('✗ '+file.name+': '+e)}}
m.style.color=out.every(s=>s.startsWith('✓'))?'var(--green)':'var(--red)';m.innerHTML=out.map(s=>s.replace(/[<&]/g,c=>({'<':'&lt;','&':'&amp;'})[c])).join('<br>')}
</script></main></body></html>
""".trimIndent()
    }
}
