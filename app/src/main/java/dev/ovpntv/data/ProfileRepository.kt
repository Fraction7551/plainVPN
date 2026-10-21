package dev.ovpntv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Profile(
    val id: String,
    val name: String,
    val remoteHost: String?,
    val remotePort: String?,
    val protocol: String?,
    val needsCredentials: Boolean,
    val username: String = "",
    val password: String = "",
) {
    val hasCredentials get() = username.isNotEmpty()
    val serverLabel get() = listOfNotNull(remoteHost, remotePort).joinToString(":").ifEmpty { "Unknown server" }
}

class InvalidProfileException(message: String) : Exception(message)

/**
 * Stores imported .ovpn profiles under filesDir/profiles/<id>/ as the original
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
) {
    companion object {
        fun parse(config: String): ConfigInfo {
            var host: String? = null
            var port: String? = null
            var proto: String? = null
            var needsCreds = false
            var hasRemote = false
            var unsupported: String? = null

            for (tokens in directives(config)) {
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
                unsupported = "This file has no 'remote' line, so it is not an OpenVPN client profile."
            }
            return ConfigInfo(host, port ?: "1194", proto ?: "udp", needsCreds, unsupported)
        }

        /** Tokenised directive lines, skipping comments and inline <blocks>. */
        fun directives(config: String): Sequence<List<String>> = sequence {
            var inBlock: String? = null
            for (raw in config.lineSequence()) {
                val line = raw.trim()
                if (inBlock != null) {
                    if (line == "</$inBlock>") inBlock = null
                    continue
                }
                if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) {
                    inBlock = line.substring(1, line.length - 1)
                    continue
                }
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                val tokens = line.removePrefix("--").split(Regex("\\s+"))
                yield(tokens)
            }
        }
    }
}
