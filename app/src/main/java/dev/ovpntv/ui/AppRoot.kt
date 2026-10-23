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
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
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
import dev.ovpntv.ui.components.PlainVpnLogo
import dev.ovpntv.ui.components.plainIcon
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.ovpntv.ui.theme.Plain

/**
 * Call just before a screen swaps out the focused item (a link to another screen, Back,
 * the licences page). Focus waits on an invisible holder instead of dropping to the drawer,
 * which would flash it open, and the next screen then takes it from the holder.
 */
val LocalHoldFocus = staticCompositionLocalOf<() -> Unit> { {} }

/** Width of the collapsed drawer rail (56dp items + 12dp padding each side). */
val RailWidth = 80.dp
/** Content starts a little right of the rail. */
val ContentStart = 104.dp

enum class Destination(val label: String, @DrawableRes val icon: Int) {
    Home("Connection", R.drawable.pi_power),
    Profiles("Profiles", R.drawable.pi_location),
    Schedule("Schedule", R.drawable.pi_clock),
    SplitTunnel("Split tunneling", R.drawable.pi_tv),
    Settings("Settings", R.drawable.pi_settings),
    Logs("Logs", R.drawable.pi_info),
}

/**
 * Left-hand navigation: a collapsed icon rail that expands into a sidebar
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

    // Focusable only while it holds focus during a swap, so the D-pad never lands on it.
    val holder = remember { FocusRequester() }
    var holding by remember { mutableStateOf(false) }
    val holdFocus: () -> Unit = remember {
        {
            holding = true
            runCatching { holder.requestFocus() }
        }
    }

    // For links inside a screen (Home's profile card and schedule chip) and Back.
    fun open(d: Destination) {
        holdFocus()
        destination = d
        focusContentRequest++
    }

    BackHandler(enabled = destination != Destination.Home) { open(Destination.Home) }

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
        CompositionLocalProvider(LocalHoldFocus provides holdFocus) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(
                    Modifier
                        .focusRequester(holder)
                        .focusProperties { canFocus = holding }
                        .onFocusChanged { if (!it.isFocused) holding = false }
                        .focusTarget(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        // Home's ground artwork runs to the right and bottom edges, so it pads itself. Its
                        // left edge still stops at the rail: if this box overlapped the drawer, D-pad right
                        // from the drawer wouldn't count it as being to the right and focus couldn't return.
                        .padding(
                            start = if (destination == Destination.Home) RailWidth else ContentStart,
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
                            onOpenProfiles = { open(Destination.Profiles) },
                            onOpenSchedule = { open(Destination.Schedule) },
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
    }
}

@Composable
private fun NavigationDrawerScope.DrawerHeader() {
    // Collapsed: the kit's p. monogram. Expanded: the plainVPN wordmark lockup.
    if (hasFocus) {
        PlainVpnLogo(34.dp, Modifier.padding(start = 16.dp))
    } else {
        Image(
            painterResource(R.drawable.pv_monogram),
            contentDescription = "plainVPN",
            modifier = Modifier.padding(start = 4.dp).size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}

/**
 * Drawer entry. Our own rather than tv-material's NavigationDrawerItem, whose expanded
 * width is fixed at 256dp: this one expands just enough for the longest label.
 * Focused = blue pill, current screen = selected tint, so the two never look the same.
 */
@Composable
private fun NavigationDrawerScope.DrawerItem(destination: Destination, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val width by animateDpAsState(if (hasFocus) ExpandedItemWidth else CollapsedItemWidth, tween(Plain.STANDARD_MS), label = "drawerItem")
    val container = when {
        focused -> Plain.Primary
        selected -> Plain.Selected
        else -> Color.Transparent
    }
    val content = when {
        focused -> Plain.OnPrimary
        selected || hasFocus -> Plain.Text
        else -> Plain.Muted
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .width(width)
            .height(CollapsedItemWidth)
            .clip(CircleShape)
            .background(container)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(plainIcon(destination.icon), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        if (hasFocus) {
            Spacer(Modifier.width(16.dp))
            Text(destination.label, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1, softWrap = false)
        }
    }
}

private val CollapsedItemWidth = 56.dp
/** Fits "Split tunneling" with a little room; the stock drawer item was 256dp. */
private val ExpandedItemWidth = 212.dp
