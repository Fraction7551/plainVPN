package dev.ovpntv.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.ovpntv.BuildConfig
import dev.ovpntv.OvpnTvApp
import dev.ovpntv.R
import dev.ovpntv.ui.MainActivity
import dev.ovpntv.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A release described by version.json on GitHub. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** Handed to Android's installer; the app is replaced (and restarted) when it finishes. */
    data class Installing(val info: UpdateInfo) : UpdateState
    /** "Install unknown apps" is off for this app; Android's settings page has been opened. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateState
}

/**
 * Self-updater for sideloaded installs: reads version.json from the latest GitHub release,
 * downloads the APK it points to, checks its SHA-256 and installs it with PackageInstaller.
 * Android only accepts the update if it is signed with the same key, so a swapped file
 * on the server can't replace the app.
 */
object Updater {
    private const val MANIFEST_URL = "https://github.com/Fraction7551/testapptv/releases/latest/download/version.json"
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED_VERSION = "notified_version"
    private const val NOTIFICATION_ID = 2
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    /** The APK is about 4 MB; this only stops a runaway download from filling the TV's storage. */
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val NOTIFICATION_CHANNEL_ID = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val busy get() = _state.value.let { it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing }

    /** Checks unless one ran within [maxAgeMillis]; [notify] posts a notification when an update is found. */
    fun checkIfDue(context: Context, maxAgeMillis: Long, notify: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < maxAgeMillis) return
        check(context, notify)
    }

    fun check(context: Context, notify: Boolean = false) {
        if (busy) return
        val app = context.applicationContext
        val quiet = _state.value is UpdateState.Available
        if (!quiet) _state.value = UpdateState.Checking
        scope.launch {
            val info = runCatching { fetchManifest() }.getOrElse {
                VpnState.log("Update check failed: ${it.message}")
                if (!quiet) _state.value = UpdateState.Failed(null, "Couldn't check for updates: ${it.message}")
                return@launch
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                VpnState.log("Update available: ${info.versionName}")
                _state.value = UpdateState.Available(info)
                if (notify) postNotification(app, info)
            } else {
                _state.value = UpdateState.UpToDate
            }
        }
    }

    /** Downloads and installs the available update, first sending the user to allow installs if needed. */
    fun install(context: Context) {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            is UpdateState.Failed -> s.info ?: return
            else -> return
        }
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            val settings = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(settings) }
            return
        }
        _state.value = UpdateState.Downloading(info, 0)
        scope.launch {
            runCatching {
                val apk = download(app, info)
                _state.value = UpdateState.Installing(info)
                VpnState.log("Installing update ${info.versionName}")
                startInstall(app, apk)
            }.onFailure {
                VpnState.log("Update failed: ${it.message}")
                _state.value = UpdateState.Failed(info, it.message ?: "Update failed")
            }
        }
    }

    /** Continues the install once the user is back from allowing "Install unknown apps". */
    fun resumeAfterPermission(context: Context) {
        if (_state.value !is UpdateState.NeedsPermission) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) install(context)
    }

    /** Called by [InstallReceiver] when Android's installer reports a result. */
    internal fun onInstallFailed(message: String) {
        val info = (_state.value as? UpdateState.Installing)?.info
        VpnState.log("Update was not installed: $message")
        _state.value = UpdateState.Failed(info, message)
    }

    private fun fetchManifest(): UpdateInfo {
        val text = open(MANIFEST_URL).use { conn ->
            conn.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_MANIFEST_BYTES + 1)
                if (bytes.size > MAX_MANIFEST_BYTES) error("version.json is too large")
                String(bytes, Charsets.UTF_8)
            }
        }
        val json = JSONObject(text)
        val apkUrl = json.getString("apkUrl")
        // Only ever download over HTTPS (the release file points at GitHub).
        if (!apkUrl.startsWith("https://")) error("the update link isn't HTTPS")
        return UpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName").take(40),
            apkUrl = apkUrl,
            sha256 = json.getString("sha256").lowercase(),
            notes = json.optString("notes").take(300),
        )
    }

    /** Reads at most [limit] bytes (InputStream.readNBytes needs Android 13). */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        open(info.apkUrl).use { conn ->
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) error("The download is far larger than the app. Not installing it.")
                        if (total > 0) _state.value = UpdateState.Downloading(info, (done * 100 / total).toInt())
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != info.sha256) {
            file.delete()
            error("The download was damaged or changed (checksum mismatch). Try again later.")
        }
        return file
    }

    private fun startInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // An app updating itself may skip the confirm screen; Android asks anyway when it must.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallReceiver::class.java), flags,
            )
            session.commit(callback.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "plainVPN/${BuildConfig.VERSION_CODE}")
        // GitHub redirects release downloads to its CDN (https to https, which this follows).
        conn.instanceFollowRedirects = true
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            val code = conn.responseCode
            conn.disconnect()
            error("server answered $code")
        }
        return conn
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun postNotification(context: Context, info: UpdateInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One notification per release, not one per daily check.
        if (prefs.getInt(KEY_NOTIFIED_VERSION, 0) >= info.versionCode) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("plainVPN ${info.versionName} is available")
            .setContentText("Open plainVPN to install the update")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification) }
        prefs.edit().putInt(KEY_NOTIFIED_VERSION, info.versionCode).apply()
    }
}
