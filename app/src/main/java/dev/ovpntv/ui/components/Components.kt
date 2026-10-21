package dev.ovpntv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.theme.Palette
import dev.ovpntv.ui.theme.positive

/** Full-width 44dp button, the Mullvad PrimaryButton shape, with a TV focus ring. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Read in graphicsLayer so the focus animation redraws without recomposing every frame.
    val scale = animateFloatAsState(if (focused) 1.03f else 1f, label = "buttonScale")
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.6f),
        ),
        border = if (focused) BorderStroke(Dimens.focusBorder, MaterialTheme.colorScheme.onPrimary) else null,
        contentPadding = PaddingValues(horizontal = Dimens.mediumPadding),
        modifier = modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }.heightIn(min = Dimens.buttonHeight).fillMaxWidth(),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Dimens.smallPadding))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PositiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    TvButton(text, onClick, modifier, containerColor = MaterialTheme.colorScheme.positive, icon = icon)

@Composable
fun NegativeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    TvButton(text, onClick, modifier, containerColor = MaterialTheme.colorScheme.error, icon = icon)

/** Mullvad-style list cell: blue row, 56/72dp tall, white text, optional leading icon and trailing content. */
@Composable
fun ListCell(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val background = if (focused) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = Dimens.FOCUS_OVERLAY_ALPHA).compositeOver(containerColor)
    } else containerColor
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle != null) Dimens.cellHeightTwoRows else Dimens.cellHeight)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .then(if (focused) Modifier.border(Dimens.focusBorder, MaterialTheme.colorScheme.onPrimary, MaterialTheme.shapes.small) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.mediumPadding, vertical = Dimens.smallPadding),
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.positive)
            Spacer(Modifier.width(Dimens.smallPadding))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(Dimens.smallPadding))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(Dimens.smallPadding))
            it()
        }
    }
}

/** Cell with a Mullvad-style switch (green when on). */
@Composable
fun SwitchCell(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, modifier: Modifier = Modifier) {
    ListCell(title = title, subtitle = subtitle, onClick = { onCheckedChange(!checked) }, modifier = modifier) {
        MullvadSwitch(checked)
    }
}

@Composable
fun MullvadSwitch(checked: Boolean) {
    val offset by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    Box(
        Modifier
            .width(46.dp)
            .height(28.dp)
            .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .padding(start = (18 * offset).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) Palette.Green else Palette.Red),
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Dimens.mediumPadding, bottom = Dimens.smallPadding),
    )
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = modifier.padding(bottom = Dimens.mediumPadding))
}

/** Rounded selectable chip (Mullvad FilterChip/FeatureChip look). */
@Composable
fun TvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        selected -> MaterialTheme.colorScheme.positive
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(Dimens.chipShape)
            .background(if (focused) MaterialTheme.colorScheme.onPrimary.copy(alpha = Dimens.FOCUS_OVERLAY_ALPHA).compositeOver(bg) else bg)
            .border(if (focused) Dimens.focusBorder else 1.dp, if (focused) MaterialTheme.colorScheme.onPrimary else bg, Dimens.chipShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.mediumPadding, vertical = Dimens.smallPadding),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Static outlined chip used to show active features, e.g. the reconnect schedule. */
@Composable
fun FeatureChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, Dimens.chipShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Banner at the top of the home screen offering the app update, with its action button. */
@Composable
fun UpdateBanner(title: String, detail: String?, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(Dimens.mediumPadding),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.positive))
        Spacer(Modifier.width(Dimens.smallPadding))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null) {
            Spacer(Modifier.width(Dimens.mediumPadding))
            TvButton(action, onAction, Modifier.width(200.dp), containerColor = MaterialTheme.colorScheme.positive)
        }
    }
}

/** Red notification banner shown at the top of the home screen for errors. */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(Dimens.mediumPadding),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Spacer(Modifier.width(Dimens.smallPadding))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
