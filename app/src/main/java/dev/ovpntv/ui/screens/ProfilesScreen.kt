package dev.ovpntv.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ovpntv.data.InvalidProfileException
import dev.ovpntv.data.Profile
import dev.ovpntv.data.ProfileRepository
import dev.ovpntv.data.Settings
import dev.ovpntv.importer.LanImportServer
import dev.ovpntv.ui.components.ListCell
import dev.ovpntv.ui.components.NegativeButton
import dev.ovpntv.ui.components.PositiveButton
import dev.ovpntv.ui.components.ScreenTitle
import dev.ovpntv.ui.components.SectionHeader
import dev.ovpntv.ui.components.TvButton
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.R
import dev.ovpntv.ui.components.plainIcon
import dev.ovpntv.ui.theme.Plain
import androidx.compose.foundation.layout.PaddingValues
import dev.ovpntv.ui.theme.warning
import dev.ovpntv.vpn.VpnState
import java.io.File

@Composable
fun ProfilesScreen(onConnect: (String?) -> Unit, onPickFile: () -> Boolean) {
    val context = LocalContext.current
    val profiles by ProfileRepository.profiles.collectAsState()
    val selectedId by Settings.selectedProfileId.collectAsState()
    var actionsFor by remember { mutableStateOf<Profile?>(null) }
    var credentialsFor by remember { mutableStateOf<Profile?>(null) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var showLanImport by remember { mutableStateOf(false) }
    val dropFolder = remember { context.getExternalFilesDir(null) }

    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.weight(1.4f)) {
            ScreenTitle("Profiles")
            if (profiles.isEmpty()) {
                Text(
                    "No profiles yet. Import an .ovpn file using one of the options on the right.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding), contentPadding = PaddingValues(Dimens.focusOutset)) {
                items(profiles, key = { it.id }) { profile ->
                    ListCell(
                        title = profile.name,
                        subtitle = buildString {
                            append("${profile.serverLabel} · ${profile.protocol?.uppercase()}")
                            if (profile.needsCredentials && !profile.hasCredentials) append(" · login required")
                        },
                        selected = profile.id == selectedId,
                        onClick = { actionsFor = profile },
                        trailing = if (profile.needsCredentials) {
                            {
                                androidx.compose.material3.Icon(
                                    plainIcon(R.drawable.pi_lock),
                                    contentDescription = if (profile.hasCredentials) "Login saved" else "Login required",
                                    tint = if (profile.hasCredentials) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.warning,
                                )
                            }
                        } else null,
                    )
                }
            }
        }

        Spacer(Modifier.width(Dimens.largePadding))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.mediumPadding)) {
            SectionHeader("Import a profile")
            TvButton("From phone or computer", { showLanImport = true }, icon = plainIcon(R.drawable.pi_cast))
            TvButton("From storage / USB", {
                if (!onPickFile()) {
                    Toast.makeText(context, "This TV has no file picker. Use the Wi-Fi import or the drop folder instead.", Toast.LENGTH_LONG).show()
                }
            }, icon = Icons.Rounded.FolderOpen)
            TvButton("Scan drop folder", { scanDropFolder(context, dropFolder) }, icon = plainIcon(R.drawable.pi_refresh))
            Text(
                "Drop folder (for adb push or file managers):\n${dropFolder?.absolutePath ?: "unavailable"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    actionsFor?.let { profile ->
        ProfileActionsDialog(
            profile = profile,
            onDismiss = { actionsFor = null },
            onConnect = {
                actionsFor = null
                Settings.setSelectedProfile(profile.id)
                if (profile.needsCredentials && !profile.hasCredentials) credentialsFor = profile else onConnect(profile.id)
            },
            onSelect = {
                Settings.setSelectedProfile(profile.id)
                actionsFor = null
            },
            onCredentials = { actionsFor = null; credentialsFor = profile },
            onRename = { actionsFor = null; renaming = profile },
            onDelete = {
                actionsFor = null
                ProfileRepository.delete(profile.id)
            },
        )
    }
    credentialsFor?.let { profile ->
        CredentialsDialog(profile, onDismiss = { credentialsFor = null }) { user, pass ->
            ProfileRepository.saveCredentials(profile.id, user, pass)
            credentialsFor = null
        }
    }
    renaming?.let { profile ->
        TextInputDialog("Rename profile", profile.name, onDismiss = { renaming = null }) {
            ProfileRepository.rename(profile.id, it)
            renaming = null
        }
    }
    if (showLanImport) LanImportDialog(onDismiss = { showLanImport = false })
}

private fun scanDropFolder(context: Context, folder: File?) {
    val files = folder?.listFiles { f -> f.isFile && (f.name.endsWith(".ovpn", true) || f.name.endsWith(".conf", true)) }.orEmpty()
    if (files.isEmpty()) {
        Toast.makeText(context, "No .ovpn files found in the drop folder", Toast.LENGTH_LONG).show()
        return
    }
    var imported = 0
    files.forEach { f ->
        try {
            ProfileRepository.import(f.name, f.readText())
            f.delete()
            imported++
        } catch (e: InvalidProfileException) {
            VpnState.log("Skipped ${f.name}: ${e.message}")
        }
    }
    Toast.makeText(context, "Imported $imported of ${files.size} file(s)", Toast.LENGTH_LONG).show()
}

@Composable
private fun ProfileActionsDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onSelect: () -> Unit,
    onCredentials: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Plain.Surface,
        shape = Dimens.cardShape,
        title = { Text(profile.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding), modifier = Modifier.width(380.dp)) {
                Text("${profile.serverLabel} · ${profile.protocol?.uppercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Dimens.smallPadding))
                PositiveButton("Connect", onConnect)
                TvButton("Use as default", onSelect)
                if (profile.needsCredentials) TvButton(if (profile.hasCredentials) "Change username & password" else "Add username & password", onCredentials)
                TvButton("Rename", onRename)
                if (confirmDelete) NegativeButton("Press again to delete", onDelete)
                else NegativeButton("Delete", { confirmDelete = true })
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun CredentialsDialog(profile: Profile, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var user by remember { mutableStateOf(profile.username) }
    var pass by remember { mutableStateOf(profile.password) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Plain.Surface,
        shape = Dimens.cardShape,
        title = { Text("Login for ${profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumPadding), modifier = Modifier.width(380.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                Text("Stored only on this device, inside the app's private storage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PositiveButton("Save", { onSave(user.trim(), pass) })
                TvButton("Cancel", onDismiss)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Plain.Surface,
        shape = Dimens.cardShape,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumPadding), modifier = Modifier.width(380.dp)) {
                OutlinedTextField(value, { value = it }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                PositiveButton("Save", { if (value.isNotBlank()) onSave(value.trim()) })
                TvButton("Cancel", onDismiss)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun LanImportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lastResult by remember { mutableStateOf<String?>(null) }
    val server = remember {
        LanImportServer(
            onImported = { name -> lastResult = "✓ Imported \"$name\"" },
            onError = { msg -> lastResult = "✗ $msg" },
        )
    }
    var started by remember { mutableStateOf(false) }
    DisposableEffect(server) {
        started = server.start()
        onDispose { server.stop() }
    }
    val addresses = remember { LanImportServer.localAddresses() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Plain.Surface,
        shape = Dimens.cardShape,
        title = { Text("Import over Wi-Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding), modifier = Modifier.width(460.dp)) {
                if (!started) {
                    Text("Could not start the import server.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("On a phone or computer on the same network, open:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (addresses.isEmpty()) Text("No local network address found", color = MaterialTheme.colorScheme.error)
                    addresses.forEach {
                        Text("http://$it:${server.port}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(Dimens.smallPadding))
                    Text("PIN", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(server.pin, style = MaterialTheme.typography.headlineLarge, color = Plain.Primary)
                    Text("Uploading works only while this window is open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lastResult?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = if (it.startsWith("✓")) Plain.Success else MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.height(Dimens.smallPadding))
                TvButton("Done", onDismiss)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Plain.Focus,
    unfocusedBorderColor = Plain.Border,
    focusedLabelColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = Plain.Canvas,
    unfocusedContainerColor = Plain.Canvas,
)
