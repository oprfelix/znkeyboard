package dev.zain.znkeyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.zain.znkeyboard.constants.AgentDefaults
import dev.zain.znkeyboard.constants.LiquidNavDimensions
import dev.zain.znkeyboard.constants.SettingsBackupDefaults
import dev.zain.znkeyboard.constants.SettingsShellDimensions
import dev.zain.znkeyboard.constants.SettingsUiDimensions
import dev.zain.znkeyboard.constants.SettingsUiTimings
import dev.zain.znkeyboard.ime.ClipboardHistoryStore
import dev.zain.znkeyboard.liquidnav.LiquidNavBar
import dev.zain.znkeyboard.liquidnav.LiquidNavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardSettings.migrateAgentProviders(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // Content scrolls behind the floating pills, so no system scrim behind 3-button navigation either.
        window.isNavigationBarContrastEnforced = false

        setContent {
            ZnKeyboardTheme {
                val stateHolder = rememberSaveableStateHolder()
                var shortcutsOpen by rememberSaveable { mutableStateOf(false) }
                AnimatedContent(
                    targetState = shortcutsOpen,
                    transitionSpec = {
                        if (targetState) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                        } else {
                            (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                        }
                    },
                    label = "settings-screen",
                ) { open ->
                    if (open) {
                        ShortcutsScreen(onBack = { shortcutsOpen = false })
                    } else {
                        stateHolder.SaveableStateProvider("settings") {
                            SettingsScreen(onOpenShortcuts = { shortcutsOpen = true })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onOpenShortcuts: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var heightScale by remember { mutableFloatStateOf(KeyboardSettings.readHeightScale(context)) }
    var bottomPaddingDp by remember { mutableStateOf(KeyboardSettings.readBottomPaddingDp(context)) }
    var functionKeyBackgroundsEnabled by remember {
        mutableStateOf(KeyboardSettings.readFunctionKeyBackgroundsEnabled(context))
    }
    var agentModeEnabled by remember { mutableStateOf(KeyboardSettings.readAgentModeEnabled(context)) }
    var agentProviderType by remember { mutableStateOf(KeyboardSettings.readAgentProviderType(context)) }
    var agentApiBaseUrl by remember { mutableStateOf(KeyboardSettings.readAgentApiBaseUrl(context)) }
    var agentModel by remember { mutableStateOf(KeyboardSettings.readAgentModel(context)) }
    var agentTranslateTarget by remember { mutableStateOf(KeyboardSettings.readAgentTranslateTarget(context).code) }
    var agentReasoningMode by remember { mutableStateOf(KeyboardSettings.readAgentReasoningMode(context)) }
    var agentReasoningTextEnabled by remember { mutableStateOf(KeyboardSettings.readAgentReasoningTextEnabled(context)) }
    var agentApiKey by remember { mutableStateOf(KeyboardSettings.readAgentApiKey(context, agentProviderType)) }
    var agentApiKeyLocked by remember { mutableStateOf(KeyboardSettings.readAgentApiKeyLocked(context, agentProviderType)) }
    var clipboardHistoryCount by remember { mutableStateOf(ClipboardHistoryStore.read(context).size) }
    var backupPasswordDialogMode by remember { mutableStateOf<BackupPasswordDialogMode?>(null) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDestinationId by rememberSaveable { mutableStateOf(SettingsDestination.Typing.id) }
    val selectedDestination = SettingsDestination.entries.firstOrNull { it.id == selectedDestinationId }
        ?: SettingsDestination.Typing
    val destinationScrollState = rememberSaveable(selectedDestinationId, saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()

    fun refreshSettingsFromStorage() {
        val nextProviderType = KeyboardSettings.readAgentProviderType(context)
        heightScale = KeyboardSettings.readHeightScale(context)
        bottomPaddingDp = KeyboardSettings.readBottomPaddingDp(context)
        functionKeyBackgroundsEnabled = KeyboardSettings.readFunctionKeyBackgroundsEnabled(context)
        agentModeEnabled = KeyboardSettings.readAgentModeEnabled(context)
        agentProviderType = nextProviderType
        agentApiBaseUrl = KeyboardSettings.readAgentApiBaseUrl(context)
        agentModel = KeyboardSettings.readAgentModel(context)
        agentReasoningMode = KeyboardSettings.readAgentReasoningMode(context)
        agentReasoningTextEnabled = KeyboardSettings.readAgentReasoningTextEnabled(context)
        agentApiKey = KeyboardSettings.readAgentApiKey(context, nextProviderType)
        agentApiKeyLocked = KeyboardSettings.readAgentApiKeyLocked(context, nextProviderType)
        clipboardHistoryCount = ClipboardHistoryStore.read(context).size
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SettingsBackupDefaults.MIME_TYPE),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri == null || password == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { writeEncryptedSettingsBackup(context, uri, password.toCharArray()) }
            }
            val message = result.fold(
                onSuccess = { "Encrypted backup exported" },
                onFailure = { error ->
                    error.message?.takeIf { it.isNotBlank() } ?: "Could not export settings"
                },
            )
            Toast.makeText(
                context,
                message,
                if (result.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        pendingImportUri = uri
        backupPasswordDialogMode = BackupPasswordDialogMode.Import
    }

    when (backupPasswordDialogMode) {
        BackupPasswordDialogMode.Export -> BackupPasswordDialog(
            mode = BackupPasswordDialogMode.Export,
            onDismiss = { backupPasswordDialogMode = null },
            onConfirm = { password ->
                backupPasswordDialogMode = null
                pendingExportPassword = password
                exportLauncher.launch(suggestedBackupFileName())
            },
        )

        BackupPasswordDialogMode.Import -> BackupPasswordDialog(
            mode = BackupPasswordDialogMode.Import,
            onDismiss = {
                backupPasswordDialogMode = null
                pendingImportUri = null
            },
            onConfirm = { password ->
                val uri = pendingImportUri
                backupPasswordDialogMode = null
                pendingImportUri = null
                if (uri != null) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { restoreEncryptedSettingsBackup(context, uri, password.toCharArray()) }
                        }
                        result
                            .onSuccess { restoreResult ->
                                refreshSettingsFromStorage()
                                Toast.makeText(
                                    context,
                                    "Backup restored (${restoreResult.clipboardEntryCount} clipboard items)",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            .onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message?.takeIf { it.isNotBlank() } ?: "Could not import settings",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                }
            },
        )

        null -> Unit
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZnKeyboardColors.Background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(destinationScrollState)
                .pointerInput(focusManager) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(
                    start = SettingsShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    end = SettingsShellDimensions.CONTENT_HORIZONTAL_PADDING,
                    top = SettingsShellDimensions.TITLE_PILL_TOP_GAP +
                        SettingsShellDimensions.TITLE_PILL_HEIGHT +
                        SettingsShellDimensions.CONTENT_CLEARANCE,
                    bottom = LiquidNavDimensions.BOTTOM_GAP +
                        LiquidNavDimensions.BAR_HEIGHT +
                        SettingsShellDimensions.CONTENT_CLEARANCE,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selectedDestination) {
                SettingsDestination.Typing -> {
                    SetupStatusCard()
                    TryTypingField()
                    AdaptiveTouchSection()
                    TypingFeelSection()
                    PinyinSection()
                }

                SettingsDestination.Keyboard -> {
                    SettingsSectionCard(verticalSpacing = 18.dp) {
                        SettingsSectionHeader(
                            title = "Look & size",
                            iconResId = R.drawable.ic_palette_24,
                        )

                        KeyboardThemeSelector()

                        LabeledSlider(
                            title = "Key height",
                            valueLabel = "${(heightScale * 100f).roundToInt()}%",
                            value = heightScale,
                            valueRange = KeyboardSettings.MIN_HEIGHT_SCALE..KeyboardSettings.MAX_HEIGHT_SCALE,
                            steps = 6,
                            onValueChange = {
                                heightScale = it
                                KeyboardSettings.saveHeightScale(context, it)
                            },
                            defaultLabel = "${(KeyboardSettings.DEFAULT_HEIGHT_SCALE * 100f).roundToInt()}%",
                            isDefault = (heightScale * 100f).roundToInt() == (KeyboardSettings.DEFAULT_HEIGHT_SCALE * 100f).roundToInt(),
                            onReset = {
                                heightScale = KeyboardSettings.DEFAULT_HEIGHT_SCALE
                                KeyboardSettings.saveHeightScale(context, KeyboardSettings.DEFAULT_HEIGHT_SCALE)
                            },
                        )

                        LabeledSlider(
                            title = "Space below keys",
                            valueLabel = "$bottomPaddingDp dp",
                            value = bottomPaddingDp.toFloat(),
                            valueRange = KeyboardSettings.MIN_BOTTOM_PADDING_DP.toFloat()..KeyboardSettings.MAX_BOTTOM_PADDING_DP.toFloat(),
                            steps = (KeyboardSettings.MAX_BOTTOM_PADDING_DP - KeyboardSettings.MIN_BOTTOM_PADDING_DP) / 4 - 1,
                            onValueChange = {
                                val value = KeyboardSettings.normalizeBottomPaddingDp((it / 4f).roundToInt() * 4)
                                bottomPaddingDp = value
                                KeyboardSettings.saveBottomPaddingDp(context, value)
                            },
                            defaultLabel = "${KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP} dp",
                            isDefault = bottomPaddingDp == KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP,
                            onReset = {
                                bottomPaddingDp = KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP
                                KeyboardSettings.saveBottomPaddingDp(context, KeyboardSettings.DEFAULT_BOTTOM_PADDING_DP)
                            },
                        )

                        SettingsSwitchRow(
                            title = "Modifier key backgrounds",
                            description = "Fill Shift, Delete, !?# and 中/EN keys. Turn off for a lighter look.",
                            checked = functionKeyBackgroundsEnabled,
                            onCheckedChange = {
                                functionKeyBackgroundsEnabled = it
                                KeyboardSettings.saveFunctionKeyBackgroundsEnabled(context, it)
                            },
                        )
                    }

                    ShortcutsSummaryCard(onOpen = onOpenShortcuts)
                }

                SettingsDestination.Tools -> {
                    AgentModeSection(
                        agentModeEnabled = agentModeEnabled,
                        agentProviderType = agentProviderType,
                        agentApiBaseUrl = agentApiBaseUrl,
                        agentModel = agentModel,
                        agentTranslateTarget = agentTranslateTarget,
                        agentReasoningMode = agentReasoningMode,
                        agentReasoningTextEnabled = agentReasoningTextEnabled,
                        agentApiKey = agentApiKey,
                        agentApiKeyLocked = agentApiKeyLocked,
                        onAgentModeEnabledChange = {
                            agentModeEnabled = it
                            KeyboardSettings.saveAgentModeEnabled(context, it)
                        },
                        onAgentProviderTypeChange = {
                            agentProviderType = it
                            KeyboardSettings.saveAgentProviderType(context, it)
                            agentModel = KeyboardSettings.readAgentModel(context, it)
                            agentApiKey = KeyboardSettings.readAgentApiKey(context, it)
                            agentApiKeyLocked = KeyboardSettings.readAgentApiKeyLocked(context, it)
                        },
                        onAgentApiBaseUrlChange = {
                            agentApiBaseUrl = it
                            KeyboardSettings.saveAgentApiBaseUrl(context, it)
                        },
                        onAgentModelChange = {
                            agentModel = it
                            KeyboardSettings.saveAgentModel(context, it, agentProviderType)
                        },
                        onAgentTranslateTargetChange = {
                            agentTranslateTarget = it
                            KeyboardSettings.saveAgentTranslateTarget(context, it)
                        },
                        onAgentReasoningModeChange = {
                            agentReasoningMode = it
                            KeyboardSettings.saveAgentReasoningMode(context, it)
                        },
                        onAgentReasoningTextEnabledChange = {
                            agentReasoningTextEnabled = it
                            KeyboardSettings.saveAgentReasoningTextEnabled(context, it)
                        },
                        onAgentApiKeyChange = {
                            agentApiKey = it
                            KeyboardSettings.saveAgentApiKey(context, agentProviderType, it)
                            if (it.isBlank() && agentApiKeyLocked) {
                                agentApiKeyLocked = false
                            }
                        },
                        onAgentApiKeyLockedChange = {
                            agentApiKeyLocked = it
                            KeyboardSettings.saveAgentApiKeyLocked(context, agentProviderType, it)
                        },
                    )

                }

                SettingsDestination.Backup -> {
                    BackupRestoreSection(
                        onExport = { backupPasswordDialogMode = BackupPasswordDialogMode.Export },
                        onImport = { importLauncher.launch(BACKUP_IMPORT_MIME_TYPES) },
                    )

                    ClipboardHistorySettingsSection(
                        entryCount = clipboardHistoryCount,
                        onClearConfirmed = {
                            ClipboardHistoryStore.clear(context)
                            clipboardHistoryCount = 0
                            Toast.makeText(context, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }

        SettingsTitlePill(
            title = selectedDestination.title,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(
                    start = SettingsShellDimensions.TITLE_PILL_START_MARGIN,
                    top = SettingsShellDimensions.TITLE_PILL_TOP_GAP,
                ),
        )

        LiquidNavBar(
            items = settingsNavItems,
            selectedIndex = selectedDestination.ordinal,
            onSelect = { index ->
                focusManager.clearFocus()
                selectedDestinationId = SettingsDestination.entries[index].id
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                )
                .padding(
                    start = LiquidNavDimensions.SIDE_MARGIN,
                    end = LiquidNavDimensions.SIDE_MARGIN,
                    bottom = LiquidNavDimensions.BOTTOM_GAP,
                ),
        )
    }
}

@Composable
private fun SettingsTitlePill(title: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(SettingsShellDimensions.TITLE_PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
    ) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + slideInVertically { it / 3 }) togetherWith
                    fadeOut(spring(stiffness = Spring.StiffnessMedium)) using
                    SizeTransform(clip = false) { _, _ ->
                        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                    }
            },
            contentAlignment = Alignment.CenterStart,
            label = "settings-title",
        ) { text ->
            Box(
                modifier = Modifier
                    .height(SettingsShellDimensions.TITLE_PILL_HEIGHT)
                    .padding(horizontal = SettingsShellDimensions.TITLE_PILL_HORIZONTAL_PADDING)
                    .semantics { heading() },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge,
                    color = ZnKeyboardColors.OnSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private val settingsNavItems = SettingsDestination.entries.map { LiquidNavItem(it.label, it.iconResId) }

@Composable
private fun ClipboardHistorySettingsSection(
    entryCount: Int,
    onClearConfirmed: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Clipboard history",
            iconResId = R.drawable.ic_history_24,
            trailingText = "$entryCount/${ClipboardHistoryStore.MAX_HISTORY}",
        )

        Text(
            text = "Long-press space on the keyboard to open clipboard history. Clearing it requires confirmation here.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(
            onClick = { confirmingClear = true },
            enabled = entryCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.DeleteContent),
            border = BorderStroke(1.dp, ZnKeyboardColors.DeleteContent.copy(alpha = 0.55f)),
        ) {
            SettingsIconTextButtonContent(R.drawable.ic_delete_24, "Clear clipboard history")
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear clipboard history?") },
            text = {
                Text("This removes $entryCount saved clipboard item${if (entryCount == 1) "" else "s"} from this keyboard.")
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClearConfirmed()
                    },
                ) {
                    Text(
                        text = "Clear",
                        color = ZnKeyboardColors.DeleteContent,
                    )
                }
            },
        )
    }
}

@Composable
private fun BackupRestoreSection(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Backup & restore",
            iconResId = R.drawable.ic_download_24,
            trailingText = "Encrypted",
        )

        Text(
            text = "Password-encrypted backups include settings, clipboard history, learned typing, and configured API keys.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onExport,
                modifier = Modifier.weight(1f),
            ) {
                SettingsIconTextButtonContent(R.drawable.ic_download_24, "Export")
            }

            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                SettingsIconTextButtonContent(R.drawable.ic_upload_24, "Import")
            }
        }
    }
}

private enum class SettingsDestination(
    val id: String,
    val label: String,
    val title: String,
    val iconResId: Int,
) {
    Typing("typing", "Typing", "Typing", R.drawable.ic_touch_24),
    Keyboard("keyboard", "Layout", "Layout & rows", R.drawable.ic_keyboard_24),
    Tools("tools", "Tools", "Tools", R.drawable.ic_tune_24),
    Backup("backup", "Backup", "Backup & clipboard", R.drawable.ic_download_24),
}

private enum class BackupPasswordDialogMode {
    Export,
    Import,
}

@Composable
private fun BackupPasswordDialog(
    mode: BackupPasswordDialogMode,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val isExport = mode == BackupPasswordDialogMode.Export
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val validationMessage = when {
        password.length < EncryptedBackupCodec.MIN_PASSWORD_LENGTH -> {
            "Use at least ${EncryptedBackupCodec.MIN_PASSWORD_LENGTH} characters."
        }

        isExport && password != confirmation -> "Passwords do not match."
        else -> null
    }

    fun dismiss() {
        password = ""
        confirmation = ""
        onDismiss()
    }

    fun submit() {
        if (validationMessage != null) return
        val submittedPassword = password
        password = ""
        confirmation = ""
        onConfirm(submittedPassword)
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = {
            Text(if (isExport) "Encrypt backup" else "Unlock backup")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (isExport) {
                        "This backup includes clipboard history and configured API keys. Enter a new password for this export."
                    } else {
                        "Enter the password used to encrypt this backup."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (isExport) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                Text(
                    text = "This password is not saved and cannot be recovered.",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                validationMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::submit,
                enabled = validationMessage == null,
            ) {
                Text(if (isExport) "Export" else "Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AgentModeSection(
    agentModeEnabled: Boolean,
    agentProviderType: KeyboardSettings.AgentProviderType,
    agentApiBaseUrl: String,
    agentModel: String,
    agentTranslateTarget: String,
    agentReasoningMode: KeyboardSettings.AgentReasoningMode,
    agentReasoningTextEnabled: Boolean,
    agentApiKey: String,
    agentApiKeyLocked: Boolean,
    onAgentModeEnabledChange: (Boolean) -> Unit,
    onAgentProviderTypeChange: (KeyboardSettings.AgentProviderType) -> Unit,
    onAgentApiBaseUrlChange: (String) -> Unit,
    onAgentModelChange: (String) -> Unit,
    onAgentTranslateTargetChange: (String) -> Unit,
    onAgentReasoningModeChange: (KeyboardSettings.AgentReasoningMode) -> Unit,
    onAgentReasoningTextEnabledChange: (Boolean) -> Unit,
    onAgentApiKeyChange: (String) -> Unit,
    onAgentApiKeyLockedChange: (Boolean) -> Unit,
) {
    SettingsSectionCard {
        SettingsSectionHeader(
            title = "Rewrite",
            subtitle = "Controls the Rewrite button in the tools row.",
            trailingContent = {
                Switch(
                    checked = agentModeEnabled,
                    onCheckedChange = onAgentModeEnabledChange,
                )
            },
        )

        Text(
            text = "Text is sent directly from this keyboard to your configured provider. Rewrite is disabled in password fields.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        ProviderTypeSelector(
            providerType = agentProviderType,
            onProviderTypeChange = onAgentProviderTypeChange,
        )

        val fixedBaseUrl = agentProviderType.fixedBaseUrl
        // Built-in providers show their endpoint read-only; custom endpoints are typed in.
        OutlinedTextField(
            shape = SettingsCapsuleShape,
            value = fixedBaseUrl ?: agentApiBaseUrl,
            onValueChange = onAgentApiBaseUrlChange,
            label = { Text("API base URL") },
            singleLine = true,
            enabled = fixedBaseUrl == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        if (agentProviderType == KeyboardSettings.AgentProviderType.Gemini) {
            GeminiModelSelector(
                apiKey = agentApiKey,
                value = agentModel,
                onValueChange = onAgentModelChange,
            )
        } else {
            ModelSelectorField(
                apiBaseUrl = agentApiBaseUrl,
                apiKey = agentApiKey,
                value = agentModel,
                onValueChange = onAgentModelChange,
            )
        }

        TranslateLanguageSelector(
            value = agentTranslateTarget,
            onValueChange = onAgentTranslateTargetChange,
        )

        // Gemini always runs with reasoning off; only custom endpoints expose it.
        if (agentProviderType == KeyboardSettings.AgentProviderType.OpenAiCompatible) {
            ReasoningControls(
                reasoningMode = agentReasoningMode,
                reasoningTextEnabled = agentReasoningTextEnabled,
                onReasoningModeChange = onAgentReasoningModeChange,
                onReasoningTextEnabledChange = onAgentReasoningTextEnabledChange,
            )
        }

        LockableSecretOutlinedTextField(
            value = agentApiKey,
            onValueChange = onAgentApiKeyChange,
            label = "API key",
            locked = agentApiKeyLocked,
            onLockedChange = onAgentApiKeyLockedChange,
            emptySupportingText = "Enter your provider's API key.",
            lockContentDescription = "Lock API key",
            unlockContentDescription = "Unlock API key",
        )
    }
}

@Composable
private fun ModelSelectorField(
    apiBaseUrl: String,
    apiKey: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var fieldFocused by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var modelLoadStatus by remember { mutableStateOf("Loading models...") }
    var debouncedQuery by remember { mutableStateOf(value) }
    val effectiveBaseUrl = apiBaseUrl

    LaunchedEffect(effectiveBaseUrl, apiKey) {
        modelOptions = emptyList()
        if (effectiveBaseUrl.isBlank()) {
            modelLoadStatus = "Enter a base URL to load models."
            return@LaunchedEffect
        }

        modelLoadStatus = "Loading models..."
        delay(MODEL_LOAD_DEBOUNCE_MS)
        val result = withContext(Dispatchers.IO) {
            fetchModelOptions(
                baseUrl = effectiveBaseUrl,
                apiKey = apiKey,
            )
        }
        result
            .onSuccess { models ->
                modelOptions = models
                modelLoadStatus = if (models.isEmpty()) {
                    "No models returned. You can still type a custom model ID."
                } else {
                    "Loaded ${models.size} models. Type to filter or pick one."
                }
            }
            .onFailure { error ->
                modelLoadStatus = error.message?.takeIf { it.isNotBlank() }
                    ?: "Could not load models. You can still type a custom model ID."
            }
    }

    LaunchedEffect(value, modelOptions) {
        delay(MODEL_QUERY_DEBOUNCE_MS)
        debouncedQuery = value
    }

    val filteredOptions = remember(modelOptions, debouncedQuery) {
        modelOptions.filterForQuery(debouncedQuery)
    }
    val showSuggestions = fieldFocused && suggestionsExpanded && filteredOptions.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            shape = SettingsCapsuleShape,
            value = value,
            onValueChange = {
                onValueChange(it)
                suggestionsExpanded = true
            },
            label = { Text("Model") },
            singleLine = true,
            trailingIcon = {
                Text(
                    text = if (modelOptions.isEmpty()) "Custom" else "${modelOptions.size}",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    fieldFocused = focusState.isFocused
                    if (focusState.isFocused) suggestionsExpanded = true
                },
        )

            FloatingPickerMenu(
                expanded = showSuggestions,
                focusable = false,
                items = filteredOptions.map { option ->
                    PickerItem(
                        id = option.id,
                        title = option.id,
                        subtitle = option.name?.takeIf { it != option.id },
                    )
                },
                onDismissRequest = { suggestionsExpanded = false },
                onItemSelected = { item ->
                    suggestionsExpanded = false
                    onValueChange(item.id)
                },
            )
        }

        if (fieldFocused && suggestionsExpanded && modelOptions.isNotEmpty() && debouncedQuery.isNotBlank() && filteredOptions.isEmpty()) {
            Surface(
                color = ZnKeyboardColors.Key,
                shape = RoundedCornerShape(SECTION_CORNER_RADIUS),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "No matching models. Keep typing to use a custom model ID.",
                    color = ZnKeyboardColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Text(
            text = modelLoadStatus,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Gemini model picker: the recommended lite model first, then the text models the user's key can
 * reach, loaded live so new Gemini releases show up without an app update.
 */
@Composable
private fun GeminiModelSelector(
    apiKey: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var liveModels by remember { mutableStateOf<List<ModelOption>>(emptyList()) }
    var loadStatus by remember { mutableStateOf("") }

    LaunchedEffect(apiKey) {
        liveModels = emptyList()
        if (apiKey.isBlank()) {
            loadStatus = "Enter your Gemini API key to see every model."
            return@LaunchedEffect
        }
        loadStatus = "Loading Gemini models..."
        delay(MODEL_LOAD_DEBOUNCE_MS)
        val result = withContext(Dispatchers.IO) {
            fetchModelOptions(baseUrl = AgentDefaults.GEMINI_API_BASE_URL, apiKey = apiKey)
        }
        result
            .onSuccess { models ->
                liveModels = models.geminiTextModels()
                loadStatus = "${liveModels.size} Gemini text models available."
            }
            .onFailure { error ->
                loadStatus = error.message?.takeIf { it.isNotBlank() } ?: "Could not load Gemini models."
            }
    }

    val recommended = AgentDefaults.DEFAULT_GEMINI_MODEL
    val items = buildList {
        add(PickerItem(id = recommended, title = recommended, subtitle = "Recommended · fast and low cost"))
        liveModels.filter { it.id != recommended }.forEach { add(PickerItem(id = it.id, title = it.id, subtitle = it.name)) }
        // Keep a model chosen earlier visible even if the live list does not offer it.
        if (value != recommended && none { it.id == value }) add(PickerItem(id = value, title = value))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            SettingsPickerLauncherButton(
                label = "Model",
                value = if (value == recommended) "$value (recommended)" else value,
                onClick = { expanded = true },
            )
            FloatingPickerMenu(
                expanded = expanded,
                items = items,
                onDismissRequest = { expanded = false },
                onItemSelected = { item ->
                    expanded = false
                    onValueChange(item.id)
                },
                maxHeight = REASONING_PICKER_MAX_HEIGHT,
            )
        }
        Text(
            text = loadStatus,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Picks the customer's language (stored as its code); the keyboard can also switch it while typing. */
@Composable
private fun TranslateLanguageSelector(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "Translate to",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val language = AgentDefaults.TRANSLATE_LANGUAGES.firstOrNull { it.code == value }
                        ?: AgentDefaults.DEFAULT_TRANSLATE_LANGUAGE
                    Text(
                        text = "${language.code} · ${language.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            FloatingPickerMenu(
                expanded = expanded,
                items = AgentDefaults.TRANSLATE_LANGUAGES.map { language ->
                    PickerItem(id = language.code, title = language.code, subtitle = language.name)
                },
                onDismissRequest = { expanded = false },
                onItemSelected = { item ->
                    expanded = false
                    onValueChange(item.id)
                },
            )
        }
        Text(
            text = "仅润色 (Polish) only polishes what you typed; AI 润译 (AI Translate) polishes and translates it into this language. The round key beside them switches language while typing. Labels follow 中/EN mode.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FloatingPickerMenu(
    expanded: Boolean,
    items: List<PickerItem>,
    onDismissRequest: () -> Unit,
    onItemSelected: (PickerItem) -> Unit,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
    maxHeight: Dp = PICKER_MAX_HEIGHT,
    menuWidth: Dp = PICKER_MENU_WIDTH,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = focusable),
        shape = RoundedCornerShape(PICKER_CORNER_RADIUS),
        containerColor = ZnKeyboardColors.Key,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .width(menuWidth)
            .background(Color.Transparent),
    ) {
        PickerListSurface(
            items = items,
            onItemSelected = onItemSelected,
            maxHeight = maxHeight,
        )
    }
}

@Composable
private fun PickerListSurface(
    items: List<PickerItem>,
    onItemSelected: (PickerItem) -> Unit,
    maxHeight: Dp,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .heightIn(max = maxHeight)
            .verticalScroll(scrollState)
            .padding(vertical = 4.dp),
    ) {
        items.forEachIndexed { index, item ->
            PickerListRow(item = item, onClick = { onItemSelected(item) })
            if (index != items.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ZnKeyboardColors.Surface),
                )
            }
        }
    }
}

@Composable
private fun PickerListRow(
    item: PickerItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = item.title,
            color = ZnKeyboardColors.OnSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                color = ZnKeyboardColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProviderTypeSelector(
    providerType: KeyboardSettings.AgentProviderType,
    onProviderTypeChange: (KeyboardSettings.AgentProviderType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        SettingsPickerLauncherButton(
            label = "Provider",
            value = providerType.label,
            onClick = { expanded = true },
        )

        FloatingPickerMenu(
            expanded = expanded,
            items = KeyboardSettings.AgentProviderType.entries.map { option ->
                PickerItem(
                    id = option.id,
                    title = option.label,
                )
            },
            onDismissRequest = { expanded = false },
            onItemSelected = { item ->
                expanded = false
                KeyboardSettings.AgentProviderType.entries.firstOrNull { it.id == item.id }
                    ?.let(onProviderTypeChange)
            },
            maxHeight = PROVIDER_PICKER_MAX_HEIGHT,
        )
    }
}

@Composable
private fun ReasoningControls(
    reasoningMode: KeyboardSettings.AgentReasoningMode,
    reasoningTextEnabled: Boolean,
    onReasoningModeChange: (KeyboardSettings.AgentReasoningMode) -> Unit,
    onReasoningTextEnabledChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val showReasoningTextSwitch = reasoningMode != KeyboardSettings.AgentReasoningMode.Off
    val providerHint = "OpenAI-compatible servers vary. Effort values are sent manually as reasoning_effort only when selected."

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Reasoning",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Model capability cannot be detected reliably from every provider, so this stays manual.",
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZnKeyboardColors.OnSurface),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "Reasoning mode",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = reasoningMode.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            FloatingPickerMenu(
                expanded = expanded,
                items = KeyboardSettings.AgentReasoningMode.entries.map { option ->
                    PickerItem(
                        id = option.id,
                        title = option.label,
                        subtitle = option.description,
                    )
                },
                onDismissRequest = { expanded = false },
                onItemSelected = { item ->
                    expanded = false
                    KeyboardSettings.AgentReasoningMode.entries.firstOrNull { it.id == item.id }
                        ?.let(onReasoningModeChange)
                },
                maxHeight = REASONING_PICKER_MAX_HEIGHT,
            )
        }

        Text(
            text = providerHint,
            color = ZnKeyboardColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        if (showReasoningTextSwitch) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Return reasoning text",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Off hides reasoning tokens when the provider supports hiding them.",
                        color = ZnKeyboardColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = reasoningTextEnabled,
                    onCheckedChange = onReasoningTextEnabledChange,
                )
            }
        }
    }
}

private const val MODEL_LOAD_DEBOUNCE_MS = SettingsUiTimings.MODEL_LOAD_DEBOUNCE_MS
private const val MODEL_QUERY_DEBOUNCE_MS = SettingsUiTimings.MODEL_QUERY_DEBOUNCE_MS
private val BACKUP_IMPORT_MIME_TYPES = SettingsBackupDefaults.IMPORT_MIME_TYPES
private val PICKER_MENU_WIDTH = SettingsUiDimensions.PICKER_MENU_WIDTH
private val PICKER_MAX_HEIGHT = SettingsUiDimensions.PICKER_MAX_HEIGHT
private val PROVIDER_PICKER_MAX_HEIGHT = SettingsUiDimensions.PROVIDER_PICKER_MAX_HEIGHT
private val REASONING_PICKER_MAX_HEIGHT = SettingsUiDimensions.REASONING_PICKER_MAX_HEIGHT
private val SKIN_TONE_PICKER_MAX_HEIGHT = SettingsUiDimensions.SKIN_TONE_PICKER_MAX_HEIGHT
private val SECTION_CORNER_RADIUS = SettingsUiDimensions.SECTION_CORNER_RADIUS
private val COMPACT_ITEM_CORNER_RADIUS = SettingsUiDimensions.COMPACT_ITEM_CORNER_RADIUS
private val PICKER_CORNER_RADIUS = SettingsUiDimensions.PICKER_CORNER_RADIUS
