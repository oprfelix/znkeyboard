@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.zain.znkeyboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.KeyboardSettings.KeyboardRow
import dev.zain.znkeyboard.constants.SettingsUiDimensions

private val UPPER = KeyboardRow.Upper.id
private val SECOND = KeyboardRow.Second.id

private data class KeySelection(val rowId: String, val index: Int)

/** Keyboard-tab entry point: a live, read-only miniature of the shortcut rows. */
@Composable
internal fun ShortcutsSummaryCard(onOpen: () -> Unit) {
    val context = LocalContext.current
    val rows = remember {
        KeyboardSettings.readKeyboardRowOrder(context).map { rowId ->
            rowId to if (rowId == UPPER) {
                KeyboardSettings.readUpperRowKeyIds(context)
            } else {
                KeyboardSettings.readSecondRowButtonIds(context)
            }
        }
    }
    val keyCount = rows.sumOf { it.second.size }

    SettingsSectionCard(
        modifier = Modifier
            .clip(RoundedCornerShape(SettingsUiDimensions.SECTION_CORNER_RADIUS))
            .clickable(onClick = onOpen),
    ) {
        SettingsSectionHeader(
            title = stringResource(R.string.shortcuts_card_title),
            iconResId = R.drawable.ic_keyboard_24,
            subtitle = pluralStringResource(R.plurals.shortcuts_card_summary, keyCount, keyCount),
            trailingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left_24),
                    contentDescription = null,
                    tint = ZnKeyboardColors.Muted,
                    modifier = Modifier.rotate(180f),
                )
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (_, keyIds) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (keyIds.isEmpty()) {
                        MiniKey(label = stringResource(R.string.shortcuts_empty_row), muted = true, modifier = Modifier.weight(1f))
                    }
                    keyIds.forEach { keyId ->
                        MiniKey(label = shortcutKeyLabel(keyId), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniKey(label: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Surface(
        color = ZnKeyboardColors.Key,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.height(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (muted) ZnKeyboardColors.Muted else ZnKeyboardColors.OnSurface,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
internal fun ShortcutsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var upperKeys by remember { mutableStateOf(KeyboardSettings.readUpperRowKeyIds(context)) }
    var secondKeys by remember { mutableStateOf(KeyboardSettings.readSecondRowButtonIds(context)) }
    var rowOrder by remember { mutableStateOf(KeyboardSettings.readKeyboardRowOrder(context)) }
    var selection by remember { mutableStateOf<KeySelection?>(null) }
    var paletteRowId by remember { mutableStateOf<String?>(null) }
    var confirmingResetRowId by remember { mutableStateOf<String?>(null) }

    fun keysFor(rowId: String) = if (rowId == UPPER) upperKeys else secondKeys
    fun maxKeysFor(rowId: String) =
        if (rowId == UPPER) KeyboardSettings.MAX_UPPER_ROW_KEYS else KeyboardSettings.MAX_SECOND_ROW_BUTTONS
    fun defaultKeysFor(rowId: String) =
        if (rowId == UPPER) KeyboardSettings.DEFAULT_UPPER_ROW_KEY_IDS else KeyboardSettings.DEFAULT_SECOND_ROW_BUTTON_IDS

    fun setKeys(rowId: String, keyIds: List<String>) {
        if (rowId == UPPER) {
            upperKeys = KeyboardSettings.normalizeUpperRowKeyIds(keyIds)
            KeyboardSettings.saveUpperRowKeyIds(context, upperKeys)
        } else {
            secondKeys = KeyboardSettings.normalizeSecondRowButtonIds(keyIds)
            KeyboardSettings.saveSecondRowButtonIds(context, secondKeys)
        }
    }

    fun toggleKey(rowId: String, keyId: String) {
        val keys = keysFor(rowId)
        if (keyId in keys) {
            setKeys(rowId, keys - keyId)
        } else if (keys.size < maxKeysFor(rowId)) {
            setKeys(rowId, keys + keyId)
        }
        selection = null
    }

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = ZnKeyboardColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shortcuts_title)) },
                navigationIcon = { BackIconButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ZnKeyboardColors.Background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSectionCard(verticalSpacing = 12.dp) {
                SettingsSectionHeader(
                    title = stringResource(R.string.shortcuts_rows),
                    iconResId = R.drawable.ic_keyboard_24,
                    subtitle = stringResource(R.string.shortcuts_rows_desc),
                )

                rowOrder.forEachIndexed { position, rowId ->
                    if (position > 0) {
                        TextButton(
                            onClick = {
                                rowOrder = KeyboardSettings.normalizeKeyboardRowOrder(rowOrder.reversed())
                                KeyboardSettings.saveKeyboardRowOrder(context, rowOrder)
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(stringResource(R.string.shortcuts_swap_rows))
                        }
                    }
                    val otherRowId = if (rowId == UPPER) SECOND else UPPER
                    ShortcutRowEditor(
                        title = keyboardRowLabel(rowId),
                        keyIds = keysFor(rowId),
                        maxKeys = maxKeysFor(rowId),
                        canReset = keysFor(rowId) != defaultKeysFor(rowId),
                        selectedIndex = selection?.takeIf { it.rowId == rowId }?.index,
                        otherRowLabel = keyboardRowLabel(otherRowId),
                        canMoveToOtherRow = keysFor(otherRowId).size < maxKeysFor(otherRowId),
                        onSelect = { index ->
                            selection = if (selection == KeySelection(rowId, index)) null else KeySelection(rowId, index)
                        },
                        onAdd = {
                            selection = null
                            paletteRowId = rowId
                        },
                        onReset = {
                            selection = null
                            confirmingResetRowId = rowId
                        },
                        onMove = { from, to ->
                            val keys = keysFor(rowId)
                            if (to in keys.indices) {
                                setKeys(rowId, keys.toMutableList().apply { add(to, removeAt(from)) })
                                selection = KeySelection(rowId, to)
                            }
                        },
                        onMoveToOtherRow = { index ->
                            val keys = keysFor(rowId)
                            val keyId = keys[index]
                            setKeys(rowId, keys.toMutableList().apply { removeAt(index) })
                            setKeys(otherRowId, keysFor(otherRowId) + keyId)
                            selection = null
                        },
                        onRemove = { index ->
                            setKeys(rowId, keysFor(rowId).toMutableList().apply { removeAt(index) })
                            selection = null
                        },
                    )
                }
            }
        }
    }

    paletteRowId?.let { rowId ->
        KeyPaletteSheet(
            rowLabel = keyboardRowLabel(rowId),
            keyIds = keysFor(rowId),
            maxKeys = maxKeysFor(rowId),
            onToggle = { keyId -> toggleKey(rowId, keyId) },
            onDismiss = { paletteRowId = null },
        )
    }

    confirmingResetRowId?.let { rowId ->
        val rowLabel = keyboardRowLabel(rowId)
        AlertDialog(
            onDismissRequest = { confirmingResetRowId = null },
            title = { Text(stringResource(R.string.slider_reset_title, rowLabel)) },
            text = { Text(stringResource(R.string.shortcuts_reset_row_text)) },
            confirmButton = {
                TextButton(onClick = {
                    setKeys(rowId, defaultKeysFor(rowId))
                    confirmingResetRowId = null
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmingResetRowId = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun BackIconButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_left_24),
            contentDescription = stringResource(R.string.action_back),
        )
    }
}

@Composable
private fun ShortcutRowEditor(
    title: String,
    keyIds: List<String>,
    maxKeys: Int,
    canReset: Boolean,
    selectedIndex: Int?,
    otherRowLabel: String,
    canMoveToOtherRow: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onMoveToOtherRow: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${keyIds.size}/$maxKeys",
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onReset, enabled = canReset) {
                Text(stringResource(R.string.action_reset))
            }
        }

        Surface(
            color = ZnKeyboardColors.Background,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FlowRow(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keyIds.forEachIndexed { index, keyId ->
                    KeyChip(
                        label = shortcutKeyLabel(keyId),
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
                if (keyIds.size < maxKeys) {
                    AddKeyChip(onClick = onAdd)
                }
            }
        }

        AnimatedVisibility(visible = selectedIndex != null && selectedIndex in keyIds.indices) {
            val index = selectedIndex ?: 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                    Icon(painterResource(R.drawable.ic_chevron_left_24), contentDescription = stringResource(R.string.action_move_left))
                }
                IconButton(onClick = { onMove(index, index + 1) }, enabled = index < keyIds.lastIndex) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_left_24),
                        contentDescription = stringResource(R.string.action_move_right),
                        modifier = Modifier.rotate(180f),
                    )
                }
                OutlinedButton(
                    onClick = { onMoveToOtherRow(index) },
                    enabled = canMoveToOtherRow,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
                ) {
                    Text(stringResource(R.string.shortcuts_move_to_row, otherRowLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete_24),
                        contentDescription = stringResource(R.string.shortcuts_remove_key),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyChip(
    label: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer else ZnKeyboardColors.Key
    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .widthIn(min = 44.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else ZnKeyboardColors.OnSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
    val border = if (selected) BorderStroke(2.dp, ZnKeyboardColors.Accent) else null
    if (onClick != null) {
        Surface(onClick = onClick, color = color, shape = shape, border = border, modifier = modifier, content = content)
    } else {
        Surface(color = color, shape = shape, border = border, modifier = modifier, content = content)
    }
}

@Composable
private fun AddKeyChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = ZnKeyboardColors.Background,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, ZnKeyboardColors.Accent),
    ) {
        Box(
            modifier = Modifier
                .height(40.dp)
                .widthIn(min = 44.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add_24),
                contentDescription = stringResource(R.string.shortcuts_add_key),
                tint = ZnKeyboardColors.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun KeyPaletteSheet(
    rowLabel: String,
    keyIds: List<String>,
    maxKeys: Int,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val full = keyIds.size >= maxKeys
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.shortcuts_add_to_row, rowLabel), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = stringResource(if (full) R.string.shortcuts_row_full else R.string.shortcuts_tap_to_add),
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("${keyIds.size}/$maxKeys", color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelLarge)
            }

            PaletteSection(stringResource(R.string.shortcuts_actions), KeyboardSettings.ACTION_KEY_OPTIONS.map { it.id to shortcutOptionLabel(it.id, it.label) }, keyIds, full, onToggle)
            PaletteSection(stringResource(R.string.shortcuts_keys), KeyboardSettings.CONTROL_KEY_OPTIONS.map { it.id to it.label }, keyIds, full, onToggle)
            PaletteSection(stringResource(R.string.shortcuts_symbols), KeyboardSettings.SYMBOL_KEY_OPTIONS.map { it.id to it.label }, keyIds, full, onToggle)
        }
    }
}

@Composable
private fun PaletteSection(
    title: String,
    options: List<Pair<String, String>>,
    keyIds: List<String>,
    full: Boolean,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = ZnKeyboardColors.Muted, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (id, label) ->
                val selected = id in keyIds
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(id) },
                    enabled = selected || !full,
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun shortcutKeyLabel(keyId: String): String {
    return shortcutOptionLabel(keyId, KeyboardSettings.labelForShortcutRowKey(keyId))
}
