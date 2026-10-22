package dev.ovpntv.data

import android.content.Context
import dev.ovpntv.vpn.ConfigSanitizer
import dev.ovpntv.vpn.WireGuardProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Which VPN engine a profile runs on; told apart by the file's contents. */
enum class ProfileKind { OpenVpn, WireGuard }

data class Profile(
    val id: String,
    val name: String,
    val remoteHost: String?,
    val remotePort: String?,
    val protocol: String?,
    val needsCredentials: Boolean,
    val username: String = "",
    val password: String = "",
    val kind: ProfileKind = ProfileKind.OpenVpn,
) {
    val hasCredentials get() = username.isNotEmpty()
    val serverLabel get() = listOfNotNull(remoteHost, remotePort).joinToString(":").ifEmpty { "Unknown server" }

    /** "OpenVPN UDP" or "WireGuard", for the UI. */
    val engineLabel get() = when (kind) {
        ProfileKind.OpenVpn -> "OpenVPN ${protocol?.uppercase().orEmpty()}".trim()
        ProfileKind.WireGuard -> "WireGuard"
    }
}

class InvalidProfileException(message: String) : Exception(message)

/**
 * Stores imported profiles (OpenVPN .ovpn or WireGuard .conf; the file is always saved as
 * config.ovpn, the kind comes from its contents) under filesDir/profiles/<id>/ as the original
 * config plus a small JSON file with the display name and saved credentials.
 */
object ProfileRepository {
    private lateinit var root: File

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    fun init(context: Context) {
        root = File(context.filesDir, "profiles").apply { mkdirs() }
        reload()
    }

    fun get(id: String?): Profile? = _profiles.value.firstOrNull { it.id == id }

    /** Profiles are text files of a few KB; anything over 1 MB is refused before it fills memory. */
    const val MAX_PROFILE_BYTES = 1024 * 1024

    /** Reads an imported file, refusing one larger than [MAX_PROFILE_BYTES]. */
    fun readProfileText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_PROFILE_BYTES) throw InvalidProfileException("The file is larger than 1 MB, so it is not a VPN profile.")
        }
        return out.toString("UTF-8")
    }

    fun configFile(profile: Profile) = File(File(root, profile.id), CONFIG_NAME)

    /** Validates and stores [config]. Returns the new profile. */
    fun import(displayName: String, config: String): Profile {
        val info = ConfigInfo.parse(config)
        info.unsupportedReason?.let { throw InvalidProfileException(it) }

        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, CONFIG_NAME).writeText(config)
        val name = displayName.removeSuffix(".ovpn").removeSuffix(".conf").ifBlank { info.remoteHost ?: "Profile" }
        writeMeta(dir, name, "", "")
        reload()
        if (Settings.selectedProfileId.value == null) Settings.setSelectedProfile(id)
        return get(id)!!
    }

    fun saveCredentials(id: String, username: String, password: String) {
        val profile = get(id) ?: return
        writeMeta(File(root, id), profile.name, username, password)
        reload()
    }

    fun rename(id: String, name: String) {
        val profile = get(id) ?: return
        writeMeta(File(root, id), name, profile.username, profile.password)
        reload()
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        if (Settings.selectedProfileId.value == id) Settings.setSelectedProfile(null)
        reload()
        if (Settings.selectedProfileId.value == null) {
            _profiles.value.firstOrNull()?.let { Settings.setSelectedProfile(it.id) }
        }
    }

    private fun writeMeta(dir: File, name: String, username: String, password: String) {
        val json = JSONObject()
            .put("name", name)
            .put("username", username)
            .put("password", password)
        File(dir, META_NAME).writeText(json.toString())
    }

    private fun reload() {
        _profiles.value = root.listFiles().orEmpty()
            .filter { File(it, CONFIG_NAME).exists() }
            .mapNotNull { dir -> runCatching { load(dir) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    private fun load(dir: File): Profile {
        val info = ConfigInfo.parse(File(dir, CONFIG_NAME).readText())
        val meta = File(dir, META_NAME).takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        return Profile(
            id = dir.name,
            name = meta?.optString("name").orEmpty().ifEmpty { dir.name },
            remoteHost = info.remoteHost,
            remotePort = info.remotePort,
            protocol = info.protocol,
            needsCredentials = info.needsCredentials,
            username = meta?.optString("username").orEmpty(),
            password = meta?.optString("password").orEmpty(),
            kind = info.kind,
        )
    }

    private const val CONFIG_NAME = "config.ovpn"
    private const val META_NAME = "meta.json"
}

/** The handful of facts about a config the UI and service need. */
data class ConfigInfo(
    val remoteHost: String?,
    val remotePort: String?,
    val protocol: String?,
    val needsCredentials: Boolean,
    val unsupportedReason: String?,
    val kind: ProfileKind = ProfileKind.OpenVpn,
) {
    companion object {
        fun parse(config: String): ConfigInfo =
            if (WireGuardProfile.looksLikeWireGuard(config)) parseWireGuard(config) else parseOpenVpn(config)

        /** Validated with the WireGuard library's own parser, so a profile that imports also starts. */
        private fun parseWireGuard(config: String): ConfigInfo {
            val (host, port) = WireGuardProfile.endpointOf(config)
            val unsupported = try {
                WireGuardProfile.parse(config)
                null
            } catch (e: InvalidProfileException) {
                e.message
            }
            return ConfigInfo(host, port ?: "51820", "udp", needsCredentials = false, unsupportedReason = unsupported, kind = ProfileKind.WireGuard)
        }

        private fun parseOpenVpn(config: String): ConfigInfo {
            var host: String? = null
            var port: String? = null
            var proto: String? = null
            var needsCreds = false
            var hasRemote = false
            var unsupported: String? = null

            for (tokens in ConfigSanitizer.directives(config)) {
                when (tokens[0]) {
                    "remote" -> if (!hasRemote) {
                        hasRemote = true
                        host = tokens.getOrNull(1)
                        port = tokens.getOrNull(2)
                        tokens.getOrNull(3)?.let { proto = it }
                    }
                    "proto" -> if (proto == null) proto = tokens.getOrNull(1)
                    "port" -> if (port == null) port = tokens.getOrNull(1)
                    "auth-user-pass" -> needsCreds = true
                    "dev" -> if (tokens.getOrNull(1)?.startsWith("tap") == true) {
                        unsupported = "TAP (layer 2) profiles are not supported by Android's VPN API. Use a 'dev tun' profile."
                    }
                    "dev-type" -> if (tokens.getOrNull(1) == "tap") {
                        unsupported = "TAP (layer 2) profiles are not supported by Android's VPN API. Use a 'dev tun' profile."
                    }
                }
            }
            if (!hasRemote && unsupported == null) {
                unsupported = "This isn't a VPN profile: an OpenVPN profile needs a 'remote' line, a WireGuard one an [Interface] section."
            }
            return ConfigInfo(host, port ?: "1194", proto ?: "udp", needsCreds, unsupported)
        }
    }
}
