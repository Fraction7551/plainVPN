package dev.ovpntv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.ovpntv.data.InvalidProfileException
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.ui.theme.AppTheme
import dev.ovpntv.update.UpdateState
import dev.ovpntv.update.Updater
import dev.ovpntv.vpn.OpenVpnService

class MainActivity : ComponentActivity() {
    private var pendingConnectProfile: String? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            OpenVpnService.connect(this, pendingConnectProfile)
        } else {
            Toast.makeText(this, "VPN permission is needed to connect", Toast.LENGTH_LONG).show()
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFromUri(it) }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppTheme {
                AppRoot(
                    onConnect = ::connect,
                    onDisconnect = { OpenVpnService.disconnect(this) },
                    onPickFile = ::pickFile,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A fresh process has forgotten any update found earlier, so it checks right away.
        val maxAge = if (Updater.state.value == UpdateState.Idle) 0L else 60 * 60 * 1000L
        Updater.checkIfDue(this, maxAgeMillis = maxAge)
        Updater.resumeAfterPermission(this)
    }

    private fun connect(profileId: String?) {
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingConnectProfile = profileId
            vpnPermission.launch(prepare)
        } else {
            OpenVpnService.connect(this, profileId)
        }
    }

    /** Returns false when the device has no document picker (common on Android TV). */
    private fun pickFile(): Boolean {
        val probe = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        if (probe.resolveActivity(packageManager) == null) return false
        return runCatching { openDocument.launch(arrayOf("*/*")) }.isSuccess
    }

    private fun importFromUri(uri: Uri) {
        try {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Profile"
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw InvalidProfileException("Could not read the file")
            val profile = ProfileRepository.import(name, text)
            Toast.makeText(this, "Imported \"${profile.name}\"", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
        }
    }
}
