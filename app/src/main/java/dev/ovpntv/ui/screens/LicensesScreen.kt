package dev.ovpntv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ovpntv.ui.components.ListCell
import dev.ovpntv.ui.components.ScreenTitle
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.theme.Plain

/**
 * plainVPN's own licence (GPL-3.0) and third-party notices. The texts live in assets/licenses,
 * copied unchanged from gnu.org, the bundled sources (openvpn-src, deps) and the Inter font. The app's source is the public repo, and the
 * GPL parts' complete source ships as source.zip on each GitHub release.
 */
private data class Component(val name: String, val license: String, val file: String)

private val COMPONENTS = listOf(
    Component("plainVPN", "GPL-3.0 · © 2026 Fraction7551", "gpl-3.0.txt"),
    Component("OpenVPN® 2.7.8", "GPL-2.0, with OpenVPN Inc's linking exception", "openvpn.txt"),
    Component("Mbed TLS 3.6.7", "Used under GPL-2.0-or-later (dual-licensed)", "mbedtls.txt"),
    Component("LZO 2.10", "GPL-2.0-or-later", "lzo.txt"),
    Component("LZ4 1.10.0 library", "BSD-2-Clause", "lz4.txt"),
    Component("Inter typeface", "SIL Open Font License 1.1", "inter-OFL.txt"),
    Component("Kotlin, kotlinx.coroutines, AndroidX and Jetpack Compose", "Apache-2.0", "apache-2.0.txt"),
)

const val SOURCE_URL = "github.com/Fraction7551/plainVPN"

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var selected by remember { mutableStateOf(COMPONENTS.first()) }
    // Blank-line paragraphs, so the D-pad can step through a long licence a block at a time.
    val paragraphs = remember(selected) {
        runCatching { context.assets.open("licenses/${selected.file}").bufferedReader().use { it.readText() } }
            .getOrDefault("Licence text unavailable.")
            .replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            // The files are hard-wrapped for an 80-column terminal; reflow each paragraph so it
            // wraps cleanly at this width, and drop "-----" / "=====" underline rows.
            .map { para ->
                para.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.matches(Regex("[-=_*]{3,}")) }.joinToString(" ")
            }
            .filter { it.isNotBlank() }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(360.dp)) {
            ScreenTitle("Open-source licences")
            Text(
                "plainVPN is free software under the GPL-3.0, provided as is with no warranty. Its source code is at " +
                    "$SOURCE_URL, and the source for OpenVPN, Mbed TLS, LZO and LZ4 as built into this app is in " +
                    "source.zip on each release there.",
                style = MaterialTheme.typography.bodySmall,
                color = Plain.Muted,
                modifier = Modifier.padding(bottom = Dimens.mediumPadding),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding), contentPadding = PaddingValues(Dimens.focusOutset)) {
                items(COMPONENTS, key = { it.file }) { c ->
                    ListCell(
                        title = c.name,
                        subtitle = c.license,
                        selected = c == selected,
                        onClick = { selected = c },
                        modifier = Modifier
                            .then(if (c == COMPONENTS.first()) Modifier.focusRequester(firstFocus) else Modifier)
                            // Moving through the list shows each text straight away.
                            .onFocusChanged { if (it.isFocused) selected = c },
                    )
                }
            }
        }
        Spacer(Modifier.width(Dimens.largePadding))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Dimens.smallPadding),
            contentPadding = PaddingValues(Dimens.mediumPadding),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Plain.Surface, Dimens.cardShape),
        ) {
            items(paragraphs) { LicenseParagraph(it) }
        }
    }
}

/** Focusable so the remote can scroll the text; the focused block gets the selected tint. */
@Composable
private fun LicenseParagraph(text: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (focused) Plain.Text else Plain.Muted,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) Plain.Selected else Plain.Surface, Dimens.controlShape)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
