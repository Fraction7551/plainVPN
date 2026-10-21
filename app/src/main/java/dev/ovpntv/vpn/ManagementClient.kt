package dev.ovpntv.vpn

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque

/**
 * Talks to OpenVPN's management interface over its UNIX socket.
 *
 * On Android, OpenVPN hands sockets to protect() and receives the tun fd as
 * SCM_RIGHTS ancillary data on this socket, so fds received alongside a read
 * are queued until the NEED-OK line that refers to them is processed.
 */
class ManagementClient(
    private val socketFile: File,
    private val onLine: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val socket = LocalSocket()
    private lateinit var input: InputStream
    /** Null until [connect] succeeds; commands sent before then are dropped. */
    @Volatile private var output: OutputStream? = null
    private val receivedFds = ArrayDeque<FileDescriptor>()
    @Volatile private var closed = false

    /** Waits for OpenVPN to create the socket, then connects. */
    fun connect(timeoutMillis: Long, isProcessAlive: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastError: IOException? = null
        while (System.currentTimeMillis() < deadline && isProcessAlive()) {
            if (socketFile.exists()) {
                try {
                    socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    input = socket.inputStream
                    synchronized(this) { output = socket.outputStream }
                    Thread(::readLoop, "ovpn-management").start()
                    return
                } catch (e: IOException) {
                    lastError = e
                }
            }
            Thread.sleep(100)
        }
        throw IOException("Could not reach the OpenVPN management socket", lastError)
    }

    fun send(command: String) {
        synchronized(this) {
            // e.g. a network change reported while OpenVPN is still starting.
            val out = output ?: return
            if (closed) return
            try {
                out.write("$command\n".toByteArray())
                out.flush()
            } catch (e: IOException) {
                VpnState.log("Management write failed: ${e.message}")
            }
        }
    }

    /** Sends [command] with [fd] attached as ancillary data. */
    fun sendWithFd(command: String, fd: FileDescriptor) {
        synchronized(this) {
            val out = output ?: return
            if (closed) return
            try {
                socket.setFileDescriptorsForSend(arrayOf(fd))
                out.write("$command\n".toByteArray())
                out.flush()
            } catch (e: IOException) {
                VpnState.log("Management write failed: ${e.message}")
            } finally {
                socket.setFileDescriptorsForSend(null)
            }
        }
    }

    fun takeReceivedFd(): FileDescriptor? = synchronized(receivedFds) { receivedFds.pollFirst() }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    private fun readLoop() {
        val buffer = ByteArray(4096)
        val pending = StringBuilder()
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                socket.ancillaryFileDescriptors?.let { fds ->
                    synchronized(receivedFds) { fds.forEach { receivedFds.addLast(it) } }
                }
                pending.append(String(buffer, 0, n))
                while (true) {
                    val nl = pending.indexOf("\n")
                    if (nl < 0) break
                    val line = pending.substring(0, nl).trimEnd('\r')
                    pending.delete(0, nl + 1)
                    if (line.isNotEmpty()) onLine(line)
                }
            }
        } catch (e: IOException) {
            if (!closed) VpnState.log("Management connection lost: ${e.message}")
        } finally {
            closed = true
            onClosed()
        }
    }

    companion object {
        /** Quotes a value for a management command argument. */
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
