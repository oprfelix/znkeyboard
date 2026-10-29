package dev.zain.znkeyboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.TranslateLanguage

/*
 * Display names for settings values. The models keep their English labels for storage and the
 * keyboard; the settings screen shows these in the app's current language.
 */

@Composable
internal fun providerLabel(type: KeyboardSettings.AgentProviderType): String = when (type) {
    KeyboardSettings.AgentProviderType.Gemini -> type.label
    KeyboardSettings.AgentProviderType.OpenAiCompatible -> stringResource(R.string.provider_openai_compatible)
}

@Composable
internal fun reasoningLabel(mode: KeyboardSettings.AgentReasoningMode): String = stringResource(
    when (mode) {
        KeyboardSettings.AgentReasoningMode.Off -> R.string.reasoning_off
        KeyboardSettings.AgentReasoningMode.Auto -> R.string.reasoning_auto
        KeyboardSettings.AgentReasoningMode.Minimal -> R.string.reasoning_minimal
        KeyboardSettings.AgentReasoningMode.Low -> R.string.reasoning_low
        KeyboardSettings.AgentReasoningMode.Medium -> R.string.reasoning_medium
        KeyboardSettings.AgentReasoningMode.High -> R.string.reasoning_high
        KeyboardSettings.AgentReasoningMode.XHigh -> R.string.reasoning_xhigh
    },
)

@Composable
internal fun reasoningDescription(mode: KeyboardSettings.AgentReasoningMode): String = stringResource(
    when (mode) {
        KeyboardSettings.AgentReasoningMode.Off -> R.string.reasoning_off_desc
        KeyboardSettings.AgentReasoningMode.Auto -> R.string.reasoning_auto_desc
        KeyboardSettings.AgentReasoningMode.Minimal -> R.string.reasoning_minimal_desc
        KeyboardSettings.AgentReasoningMode.Low -> R.string.reasoning_low_desc
        KeyboardSettings.AgentReasoningMode.Medium -> R.string.reasoning_medium_desc
        KeyboardSettings.AgentReasoningMode.High -> R.string.reasoning_high_desc
        KeyboardSettings.AgentReasoningMode.XHigh -> R.string.reasoning_xhigh_desc
    },
)

@Composable
internal fun translateLanguageName(language: TranslateLanguage): String = when (language.code) {
    "EN" -> stringResource(R.string.language_en)
    "ES" -> stringResource(R.string.language_es)
    "JA" -> stringResource(R.string.language_ja)
    "FR" -> stringResource(R.string.language_fr)
    "DE" -> stringResource(R.string.language_de)
    else -> language.name
}

@Composable
internal fun themeLabel(style: ImeColors.Style): String = stringResource(
    when (style) {
        ImeColors.Style.MaterialYou -> R.string.theme_material_you
        ImeColors.Style.ClassicBlack -> R.string.theme_classic_black
    },
)

@Composable
internal fun keyboardRowLabel(rowId: String): String = when (rowId) {
    KeyboardSettings.KeyboardRow.Upper.id -> stringResource(R.string.row_a)
    KeyboardSettings.KeyboardRow.Second.id -> stringResource(R.string.row_b)
    else -> KeyboardSettings.labelForKeyboardRow(rowId)
}

/** Shortcut-row keys; only the action keys have words to translate, the rest are key names and symbols. */
@Composable
internal fun shortcutOptionLabel(keyId: String, label: String): String = when (keyId) {
    "rewrite" -> stringResource(R.string.key_rewrite)
    "clipboard" -> stringResource(R.string.key_clipboard)
    else -> label
}

/** Backup and storage errors are thrown in English; this shows them in the app language. */
internal fun localizedError(context: Context, message: String?): String? {
    val text = message?.takeIf { it.isNotBlank() } ?: return null
    SHORT_PASSWORD_ERROR.matchEntire(text)?.let { match ->
        return context.getString(R.string.error_backup_short_password, match.groupValues[1].toInt())
    }
    val resId = ERROR_MESSAGES[text] ?: return text
    return context.getString(resId)
}

private val SHORT_PASSWORD_ERROR = Regex("Use a backup password with at least (\\d+) characters\\.")

private val ERROR_MESSAGES = mapOf(
    "Could not encrypt backup." to R.string.error_backup_encrypt,
    "Enter the backup password." to R.string.error_backup_need_password,
    "Not a ZnKeyboard encrypted backup." to R.string.error_backup_not_backup,
    "Not a supported ZnKeyboard encrypted backup." to R.string.error_backup_unsupported,
    "Backup is missing encryption details." to R.string.error_backup_missing_details,
    "Unsupported backup encryption settings." to R.string.error_backup_bad_settings,
    "Backup encryption details are invalid." to R.string.error_backup_invalid_details,
    "Wrong password or a corrupted backup." to R.string.error_backup_wrong_password,
    "Could not open export file." to R.string.error_open_export_file,
)
