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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.ovpntv.ui.theme.Palette

enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Profiles("Profiles", Icons.AutoMirrored.Rounded.List),
    Schedule("Schedule", Icons.Rounded.Schedule),
    SplitTunnel("Split tunneling", Icons.Rounded.AltRoute),
    Settings("Settings", Icons.Rounded.Settings),
    Logs("Logs", Icons.Rounded.Terminal),
}

/**
 * Left-hand navigation drawer, styled like Mullvad's NavigationDrawerTv:
 * collapsed icons over a black-to-transparent gradient that expands on focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(onConnect: (String?) -> Unit, onDisconnect: () -> Unit, onPickFile: () -> Boolean) {
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val gradient = remember { Brush.horizontalGradient(listOf(Color.Black, Color.Transparent)) }
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
        scrimBrush = gradient,
        drawerContent = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxHeight()
                    .background(gradient)
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
                .padding(start = 88.dp, end = Dimens.screenHorizontal, top = Dimens.screenVertical, bottom = Dimens.screenVertical)
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = if (hasFocus) 16.dp else 12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(40.dp),
        )
        if (hasFocus) {
            Text(
                "OpenVPN TV",
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = Palette.OffWhite,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun NavigationDrawerScope.DrawerItem(destination: Destination, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        leadingContent = { Icon(destination.icon, contentDescription = null) },
        colors = NavigationDrawerItemDefaults.colors(
            contentColor = Palette.OffWhite,
            inactiveContentColor = Palette.OffWhite,
            focusedContainerColor = Palette.Blue,
            focusedContentColor = Palette.OffWhite,
            selectedContainerColor = Palette.BlueOnDarkBlue40,
            selectedContentColor = Palette.OffWhite,
            focusedSelectedContainerColor = Palette.Blue,
            focusedSelectedContentColor = Palette.OffWhite,
            pressedContainerColor = Palette.Blue,
            pressedContentColor = Palette.OffWhite,
        ),
    ) {
        Text(destination.label, maxLines = 1, overflow = TextOverflow.Clip)
    }
}
