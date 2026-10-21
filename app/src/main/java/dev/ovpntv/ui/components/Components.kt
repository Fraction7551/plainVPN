package dev.ovpntv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import dev.ovpntv.R
import dev.ovpntv.ui.theme.Dimens
import dev.ovpntv.ui.theme.Plain

/** A UI icon (res/drawable/pi_*.xml: 24dp, 1.8 stroke; see design-tools). */
@Composable
fun plainIcon(@DrawableRes id: Int): ImageVector = ImageVector.vectorResource(id)

/**
 * TV focus indicator: a 3dp white ring just inside the control and a 3dp
 * light-blue ring outside it with a 3dp gap, so focus never depends on colour alone.
 * It draws outside the bounds, so scrolling parents need [Dimens.focusOutset] padding.
 */
fun Modifier.focusRing(focused: Boolean, radius: Dp = 12.dp): Modifier = drawWithContent {
    drawContent()
    if (!focused) return@drawWithContent
    val ring = Dimens.focusRing.toPx()
    val gap = Dimens.focusGap.toPx()
    val r = radius.toPx()
    drawRoundRect(
        color = Plain.Text,
        topLeft = Offset(ring / 2, ring / 2),
        size = Size(size.width - ring, size.height - ring),
        cornerRadius = CornerRadius(r - ring / 2),
        style = Stroke(ring),
    )
    val out = gap + ring / 2
    drawRoundRect(
        color = Plain.Focus,
        topLeft = Offset(-out, -out),
        size = Size(size.width + 2 * out, size.height + 2 * out),
        cornerRadius = CornerRadius(r + out),
        style = Stroke(ring),
    )
}

/**
 * The app's name: the kit's outlined "plain." wordmark (never typeset) followed by "VPN"
 * in Inter, sized so the product name sits on the wordmark's baseline.
 */
@Composable
fun PlainVpnLogo(height: Dp, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "plainVPN" }) {
        Image(painterResource(R.drawable.pv_wordmark), contentDescription = null, modifier = Modifier.height(height))
        Text(
            "VPN",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (height.value * 0.42f).sp, lineHeight = (height.value * 0.42f).sp),
            color = Plain.Muted,
            // The wordmark's artboard has room below the baseline (descender of the p); lift to match.
            modifier = Modifier.padding(start = (height.value * 0.08f).dp, bottom = (height.value * 0.2f).dp),
        )
    }
}

enum class ButtonStyle { Primary, Secondary, Destructive }

/** Kit button: 48dp tall, 12dp corners. Primary is blue with a dark label; secondary is a bordered surface. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Secondary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val (container, content) = when (style) {
        ButtonStyle.Primary -> Plain.Primary to Plain.OnPrimary
        ButtonStyle.Secondary -> (if (focused) Plain.Raised else Plain.Surface) to Plain.Text
        ButtonStyle.Destructive -> Plain.Danger to Plain.OnDanger
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = Dimens.buttonHeight)
            .fillMaxWidth()
            .focusRing(focused)
            .clip(Dimens.controlShape)
            .background(if (enabled) container else container.copy(alpha = 0.45f))
            .then(if (style == ButtonStyle.Secondary && !focused) Modifier.border(1.dp, Plain.Border, Dimens.controlShape) else Modifier)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.mediumPadding, vertical = 10.dp),
    ) {
        val tint = if (enabled) content else content.copy(alpha = 0.7f)
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Dimens.smallPadding))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PositiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    TvButton(text, onClick, modifier, style = ButtonStyle.Primary, icon = icon)

@Composable
fun NegativeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    TvButton(text, onClick, modifier, style = ButtonStyle.Destructive, icon = icon)

/** Blue check badge for the current selection (shape + colour, not colour alone). */
@Composable
fun SelectedBadge(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(28.dp).background(Plain.Primary, CircleShape)) {
        Icon(plainIcon(R.drawable.pi_check), contentDescription = "Selected", tint = Plain.OnPrimary, modifier = Modifier.size(18.dp))
    }
}

/** List row on a surface card: 56/72dp tall, white title, muted subtitle, optional trailing content. */
@Composable
fun ListCell(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
    containerColor: Color = Plain.Surface,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle != null) Dimens.cellHeightTwoRows else Dimens.cellHeight)
            .focusRing(focused)
            .clip(Dimens.controlShape)
            .background(if (focused) Plain.Raised else containerColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.mediumPadding, vertical = Dimens.smallPadding),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = Plain.Text, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Plain.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Plain.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(Dimens.smallPadding))
            it()
        }
        if (selected) {
            Spacer(Modifier.width(Dimens.smallPadding))
            SelectedBadge()
        }
    }
}

/** Cell with a switch (blue track when on). */
@Composable
fun SwitchCell(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, modifier: Modifier = Modifier) {
    ListCell(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.semantics { stateDescription = if (checked) "On" else "Off" },
    ) {
        PlainSwitch(checked)
    }
}

@Composable
fun PlainSwitch(checked: Boolean) {
    val offset by animateFloatAsState(if (checked) 1f else 0f, tween(Plain.STANDARD_MS), label = "switch")
    val track by animateColorAsState(if (checked) Plain.Primary else Color.Transparent, tween(Plain.STANDARD_MS), label = "track")
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .background(track, CircleShape)
            .border(2.dp, if (checked) Plain.Primary else Plain.Border, CircleShape)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset(x = (22 * offset).dp)
                .size(22.dp)
                .background(if (checked) Plain.Text else Plain.Muted, CircleShape),
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Plain.Muted,
        modifier = modifier.padding(top = Dimens.mediumPadding, bottom = Dimens.smallPadding),
    )
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Plain.Text, modifier = modifier.padding(bottom = Dimens.mediumPadding))
}

/** Selectable option. Selected = blue with a check mark, so the state isn't colour-only. */
@Composable
fun TvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        selected -> Plain.Primary
        focused -> Plain.Raised
        else -> Plain.Surface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .heightIn(min = 44.dp)
            .focusRing(focused)
            .clip(Dimens.chipShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.mediumPadding, vertical = Dimens.smallPadding),
    ) {
        val fg = if (selected) Plain.OnPrimary else Plain.Text
        if (selected) Icon(plainIcon(R.drawable.pi_check), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Static outlined chip used to show active features, e.g. the reconnect schedule. */
@Composable
fun FeatureChip(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .border(1.dp, Plain.Border, Dimens.chipShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Plain.Muted, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Plain.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Kit card: surface fill, 16dp corners, 1dp border. */
@Composable
fun PlainCard(modifier: Modifier = Modifier, padding: Dp = Dimens.mediumPadding, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(Dimens.cardShape)
            .background(Plain.Surface)
            .border(1.dp, Plain.Raised, Dimens.cardShape)
            .padding(padding),
        content = content,
    )
}

/** Banner offering the app update, with its action button. */
@Composable
fun UpdateBanner(title: String, detail: String?, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    PlainCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(plainIcon(R.drawable.pi_refresh), contentDescription = null, tint = Plain.Primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Plain.Text)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Plain.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            TvButton(action, onAction, style = ButtonStyle.Primary)
        }
    }
}

/** Error banner: warning symbol + text, so it never relies on colour alone. */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(Dimens.cardShape)
            .background(Plain.Surface)
            .border(1.dp, Plain.Danger, Dimens.cardShape)
            .padding(horizontal = Dimens.mediumPadding, vertical = 12.dp),
    ) {
        Icon(plainIcon(R.drawable.pi_warning), contentDescription = null, tint = Plain.Danger, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Plain.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
