package dev.zain.znkeyboard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.constants.SettingsUiDimensions

/** True inside a [SettingsGroup]: sections then draw no card of their own and share the group's. */
internal val LocalSettingsGrouped = staticCompositionLocalOf { false }

/** Where a grouped row's divider starts: past the icon tile, under the text, as on iOS. */
private val GROUP_DIVIDER_INSET = SettingsUiDimensions.ROW_HORIZONTAL_PADDING +
    SettingsUiDimensions.ICON_TILE_SIZE + SettingsUiDimensions.ICON_TILE_GAP

/** A small caption above a group of rows. */
@Composable
internal fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = ZnKeyboardColors.Muted,
        modifier = modifier.padding(start = 8.dp, top = 6.dp),
    )
}

/**
 * One white card holding several rows with hairlines between them, under an optional caption:
 * the grouped list of the iOS app. Each item draws only its row; a section inside draws no card.
 */
@Composable
internal fun SettingsGroup(label: String?, items: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { SettingsGroupLabel(it) }
        SettingsCard {
            CompositionLocalProvider(LocalSettingsGrouped provides true) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = ZnKeyboardColors.Divider,
                            modifier = Modifier.padding(start = GROUP_DIVIDER_INSET),
                        )
                    }
                    item()
                }
            }
        }
    }
}

/** The white rounded card under every group, with a faint shadow. */
@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(SettingsUiDimensions.SECTION_CORNER_RADIUS)
    Surface(
        color = ZnKeyboardColors.Surface,
        shape = shape,
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .shadow(SettingsUiDimensions.CARD_SHADOW, shape, ambientColor = CARD_SHADOW_COLOR, spotColor = CARD_SHADOW_COLOR),
    ) {
        Column(content = content)
    }
}

private val CARD_SHADOW_COLOR = Color(0x331E2A5A)

/** A row's icon: the accent glyph on a pale accent tile. */
@Composable
internal fun SettingsIconTile(iconResId: Int, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SettingsUiDimensions.ICON_TILE_SIZE)
            .background(ZnKeyboardColors.AccentSoft, RoundedCornerShape(SettingsUiDimensions.ICON_TILE_RADIUS)),
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = null,
            tint = ZnKeyboardColors.Accent,
            modifier = Modifier.size(SettingsUiDimensions.ICON_TILE_GLYPH),
        )
    }
}

/** A section: its own card, or, inside a [SettingsGroup], a row of the group's card. */
@Composable
internal fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    verticalSpacing: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body = @Composable {
        Column(
            modifier = Modifier.padding(
                horizontal = SettingsUiDimensions.ROW_HORIZONTAL_PADDING,
                vertical = SettingsUiDimensions.ROW_VERTICAL_PADDING + 2.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
    if (LocalSettingsGrouped.current) {
        Box(modifier.fillMaxWidth()) { body() }
    } else {
        SettingsCard(modifier) { body() }
    }
}

/**
 * A section that shows only its row until tapped, then opens in place. [summary] stands in for
 * the hidden settings while closed.
 */
@Composable
internal fun CollapsibleSettingsSection(
    title: String,
    iconResId: Int,
    summary: String,
    verticalSpacing: androidx.compose.ui.unit.Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    // The chevron points right, as on iOS, and turns down once open.
    val chevronRotation by animateFloatAsState(if (expanded) -90f else 180f, label = "chevron")
    val body = @Composable {
        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(
                        horizontal = SettingsUiDimensions.ROW_HORIZONTAL_PADDING,
                        vertical = SettingsUiDimensions.ROW_VERTICAL_PADDING,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSectionHeader(
                    title = title,
                    iconResId = iconResId,
                    subtitle = summary.takeUnless { expanded || it.isBlank() },
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left_24),
                    contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                    tint = ZnKeyboardColors.Muted.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp).rotate(chevronRotation),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(
                        start = SettingsUiDimensions.ROW_HORIZONTAL_PADDING,
                        end = SettingsUiDimensions.ROW_HORIZONTAL_PADDING,
                        top = 4.dp,
                        bottom = 18.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                    content = content,
                )
            }
        }
    }
    if (LocalSettingsGrouped.current) body() else SettingsCard { body() }
}

@Composable
internal fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    iconResId: Int? = null,
    subtitle: String? = null,
    trailingText: String? = null,
    trailingColor: Color = ZnKeyboardColors.Muted,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            iconResId?.let {
                SettingsIconTile(it)
                Spacer(Modifier.width(SettingsUiDimensions.ICON_TILE_GAP))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        trailingContent?.let {
            Spacer(Modifier.width(12.dp))
            it(this)
        } ?: trailingText?.let {
            Text(
                text = it,
                color = trailingColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
internal fun SettingsIconTextButtonContent(
    iconResId: Int,
    text: String,
) {
    Icon(
        painter = painterResource(iconResId),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.width(8.dp))
    Text(text)
}

/** Capsule outline shared by the settings text fields and pickers. */
internal val SettingsCapsuleShape = RoundedCornerShape(percent = 50)

/** A capsule that opens a picker when tapped anywhere on it. */
@Composable
internal fun SettingsPickerLauncherButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
    ) {
        leadingContent?.invoke(this)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
internal fun LockableSecretOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    locked: Boolean,
    onLockedChange: (Boolean) -> Unit,
    emptySupportingText: String,
    lockContentDescription: String,
    unlockContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val effectivelyLocked = locked && value.isNotBlank()
    OutlinedTextField(
        shape = SettingsCapsuleShape,
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = !effectivelyLocked,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(
                onClick = {
                    if (effectivelyLocked) {
                        onLockedChange(false)
                    } else if (value.isNotBlank()) {
                        onLockedChange(true)
                    }
                },
                enabled = value.isNotBlank(),
            ) {
                Icon(
                    painter = painterResource(
                        if (effectivelyLocked) R.drawable.ic_lock_24 else R.drawable.ic_lock_open_24,
                    ),
                    contentDescription = if (effectivelyLocked) unlockContentDescription else lockContentDescription,
                    tint = if (effectivelyLocked) ZnKeyboardColors.Accent else ZnKeyboardColors.Muted,
                )
            }
        },
        supportingText = {
            Text(
                text = if (effectivelyLocked) {
                    stringResource(R.string.secret_locked)
                } else if (value.isNotBlank()) {
                    stringResource(R.string.secret_unlocked)
                } else {
                    emptySupportingText
                },
                color = ZnKeyboardColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}
