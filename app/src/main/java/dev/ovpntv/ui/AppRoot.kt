package dev.ovpntv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import dev.ovpntv.R
import dev.ovpntv.ui.screens.HomeScreen
import dev.ovpntv.ui.screens.LogsScreen
import dev.ovpntv.ui.screens.ProfilesScreen
import dev.ovpntv.ui.screens.ScheduleScreen
import dev.ovpntv.ui.screens.SettingsScreen
import dev.ovpntv.ui.screens.SplitTunnelScreen
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.components.plainIcon
import dev.ovpntv.ui.theme.Plain

enum class Destination(val label: String, @DrawableRes val icon: Int) {
    Home("Connection", R.drawable.pi_power),
    Profiles("Profiles", R.drawable.pi_location),
    Schedule("Schedule", R.drawable.pi_clock),
    SplitTunnel("Split tunneling", R.drawable.pi_tv),
    Settings("Settings", R.drawable.pi_settings),
    Logs("Logs", R.drawable.pi_info),
}

/**
 * Left-hand navigation: a collapsed icon rail that expands into the kit's sidebar
 * (wordmark, icon + label items, current item on the selected tint) when it takes focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(onConnect: (String?) -> Unit, onDisconnect: () -> Unit, onPickFile: () -> Boolean) {
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scrim = remember { SolidColor(Plain.Canvas.copy(alpha = 0.6f)) }
    val contentFocus = remember { FocusRequester() }
    val itemFocus = remember { Destination.entries.associateWith { FocusRequester() } }
    var contentHasFocus by remember { mutableStateOf(false) }
    // Bumped when a drawer item is picked. Focus moves into the new screen once it
    // has composed (and loaded something focusable), which also closes the drawer.
    var focusContentRequest by remember { mutableStateOf(0) }

    LaunchedEffect(focusContentRequest) {
        if (focusContentRequest == 0) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            if (contentHasFocus) return@LaunchedEffect
            runCatching { contentFocus.requestFocus() }
        }
    }

    BackHandler(enabled = destination != Destination.Home) { destination = Destination.Home }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimBrush = scrim,
        drawerContent = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxHeight()
                    .background(Plain.Surface)
                    .padding(vertical = Dimens.screenVertical, horizontal = 12.dp)
                    // Opening the drawer lands on the current screen, not the item nearest the old focus.
                    .focusProperties { enter = { itemFocus.getValue(destination) } }
                    .focusGroup(),
            ) {
                DrawerHeader()
                Box(Modifier.weight(1f))
                Destination.entries.forEach { d ->
                    DrawerItem(d, selected = d == destination, Modifier.focusRequester(itemFocus.getValue(d))) {
                        destination = d
                        drawerState.setValue(DrawerValue.Closed)
                        focusContentRequest++
                    }
                }
                Box(Modifier.weight(1f))
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Home lets its illustration panel run to the right and bottom edges, so it pads itself.
                .padding(
                    start = 88.dp + Dimens.mediumPadding,
                    end = if (destination == Destination.Home) 0.dp else Dimens.screenHorizontal,
                    top = if (destination == Destination.Home) 0.dp else Dimens.screenVertical,
                    bottom = if (destination == Destination.Home) 0.dp else Dimens.screenVertical,
                )
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusRequester(contentFocus)
                .focusGroup(),
        ) {
            when (destination) {
                Destination.Home -> HomeScreen(
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onOpenProfiles = { destination = Destination.Profiles },
                    onOpenSchedule = { destination = Destination.Schedule },
                )
                Destination.Profiles -> ProfilesScreen(onConnect = onConnect, onPickFile = onPickFile)
                Destination.Schedule -> ScheduleScreen()
                Destination.SplitTunnel -> SplitTunnelScreen(onConnect = onConnect)
                Destination.Settings -> SettingsScreen()
                Destination.Logs -> LogsScreen()
            }
        }
    }
}

@Composable
private fun NavigationDrawerScope.DrawerHeader() {
    // Collapsed: the product pictogram. Expanded: the plain tunnel wordmark (never typeset).
    if (hasFocus) {
        Image(
            painterResource(R.drawable.plain_tunnel_wordmark),
            contentDescription = "plain tunnel",
            modifier = Modifier.padding(start = 16.dp).height(36.dp),
        )
    } else {
        Image(
            painterResource(R.drawable.plain_tunnel_foreground),
            contentDescription = "plain tunnel",
            modifier = Modifier.padding(start = 4.dp).size(48.dp),
        )
    }
}

@Composable
private fun NavigationDrawerScope.DrawerItem(destination: Destination, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leadingContent = { Icon(plainIcon(destination.icon), contentDescription = null) },
        colors = NavigationDrawerItemDefaults.colors(
            contentColor = Plain.Text,
            inactiveContentColor = Plain.Muted,
            focusedContainerColor = Plain.Apricot,
            focusedContentColor = Plain.Pine,
            selectedContainerColor = Plain.Selected,
            selectedContentColor = Plain.Text,
            focusedSelectedContainerColor = Plain.Apricot,
            focusedSelectedContentColor = Plain.Pine,
            pressedContainerColor = Plain.Apricot,
            pressedContentColor = Plain.Pine,
        ),
    ) {
        Text(destination.label, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
