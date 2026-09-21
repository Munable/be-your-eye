package app.beyoureyes.monitor.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.beyoureyes.monitor.ProductColors
import app.beyoureyes.monitor.R

internal enum class ProductTone {
    NEUTRAL,
    ACTIVE,
    WAITING,
    ERROR,
}

@Composable
internal fun ProductTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    eyebrow: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 62.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
            Column(modifier = Modifier.padding(start = if (onBack == null) 0.dp else 4.dp)) {
                eyebrow?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = ProductColors.Cyan,
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = ProductColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(content = trailing)
    }
}

@Composable
internal fun ProductSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            count?.let {
                Text(
                    it.toString(),
                    modifier = Modifier.padding(start = 8.dp),
                    color = ProductColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                modifier = Modifier.clickable(onClick = onAction).padding(8.dp),
                color = ProductColors.Cyan,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun ProductPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tone: ProductTone = ProductTone.NEUTRAL,
    content: @Composable () -> Unit,
) {
    val background = when (tone) {
        ProductTone.NEUTRAL -> ProductColors.Surface
        ProductTone.ACTIVE -> ProductColors.GreenSoft
        ProductTone.WAITING -> ProductColors.AmberSoft
        ProductTone.ERROR -> ProductColors.ErrorSoft
    }
    val border = when (tone) {
        ProductTone.NEUTRAL -> ProductColors.Border
        ProductTone.ACTIVE -> ProductColors.Green.copy(alpha = 0.45f)
        ProductTone.WAITING -> ProductColors.Amber.copy(alpha = 0.45f)
        ProductTone.ERROR -> ProductColors.Error.copy(alpha = 0.45f)
    }
    val animatedBackground by animateColorAsState(background, tween(220), label = "panelBackground")
    val animatedBorder by animateColorAsState(border, tween(220), label = "panelBorder")
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberProductPressScale(interactionSource)
    Card(
        modifier = modifier.graphicsLayer {
            scaleX = pressScale.value
            scaleY = pressScale.value
        }.clip(MaterialTheme.shapes.large).then(
            if (onClick != null) {
                Modifier.semantics(mergeDescendants = true) { role = Role.Button }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
            } else {
                Modifier
            },
        ),
        colors = CardDefaults.cardColors(
            containerColor = animatedBackground,
            contentColor = ProductColors.TextPrimary,
        ),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (tone == ProductTone.NEUTRAL) 0.75.dp else 1.dp, animatedBorder),
        content = { Box(Modifier.fillMaxWidth().padding(18.dp)) { content() } },
    )
}

@Composable
internal fun ProductIconBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = ProductColors.Cyan,
    background: Color = ProductColors.CyanSoft,
) {
    Surface(
        modifier = modifier.size(44.dp),
        shape = MaterialTheme.shapes.medium,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    tone: ProductTone,
    modifier: Modifier = Modifier,
) {
    val (foreground, background) = when (tone) {
        ProductTone.NEUTRAL -> ProductColors.TextSecondary to ProductColors.SurfaceHighlight
        ProductTone.ACTIVE -> ProductColors.Green to ProductColors.GreenSoft
        ProductTone.WAITING -> ProductColors.Amber to ProductColors.AmberSoft
        ProductTone.ERROR -> ProductColors.Error to ProductColors.ErrorSoft
    }
    val animatedBackground by animateColorAsState(background, tween(220), label = "statusBackground")
    val animatedForeground by animateColorAsState(foreground, tween(220), label = "statusForeground")
    Surface(modifier = modifier, color = animatedBackground, shape = CircleShape) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            color = animatedForeground,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun ProductPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressScale = rememberProductPressScale(interactionSource)
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = 54.dp).graphicsLayer {
            scaleX = pressScale.value
            scaleY = pressScale.value
        },
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ProductColors.Cyan,
            contentColor = Color(0xFF002F34),
            disabledContainerColor = ProductColors.SurfaceHighlight,
            disabledContentColor = ProductColors.TextMuted,
        ),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ProductSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { contentDescription = accessibilityLabel },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = ProductColors.Background,
            checkedTrackColor = ProductColors.Cyan,
            checkedBorderColor = ProductColors.Cyan,
            uncheckedThumbColor = ProductColors.TextMuted,
            uncheckedTrackColor = ProductColors.SurfaceHighlight,
            uncheckedBorderColor = ProductColors.Border,
            disabledCheckedThumbColor = ProductColors.TextMuted,
            disabledCheckedTrackColor = ProductColors.CyanSoft,
            disabledUncheckedThumbColor = ProductColors.TextMuted.copy(alpha = 0.55f),
            disabledUncheckedTrackColor = ProductColors.SurfaceHighlight.copy(alpha = 0.55f),
        ),
    )
}

@Composable
internal fun ProductEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    ProductPanel(modifier = modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductIconBadge(
                    icon = icon,
                    contentDescription = null,
                    tint = ProductColors.TextSecondary,
                    background = ProductColors.SurfaceHighlight,
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        body,
                        modifier = Modifier.padding(top = 3.dp),
                        color = ProductColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                ProductPrimaryButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun ProductMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = ProductColors.TextPrimary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ProductColors.TextMuted)
    }
}
