package dev.ovpntv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ovpntv.ui.components.ScreenTitle
import dev.ovpntv.ui.components.TvButton
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.vpn.VpnState

@Composable
fun LogsScreen() {
    val log by VpnState.log.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Logs", Modifier.weight(1f))
            TvButton("Clear", VpnState::clearLog, Modifier.width(160.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                .padding(Dimens.smallPadding),
        ) {
            if (log.isEmpty()) item { LogLine("No log output yet. Connect to see OpenVPN's output here.") }
            itemsIndexed(log) { _, line -> LogLine(line) }
        }
        Spacer(Modifier.padding(top = Dimens.smallPadding))
    }
}

/** Each line is focusable so the D-pad can scroll through the log. */
@Composable
private fun LogLine(text: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
