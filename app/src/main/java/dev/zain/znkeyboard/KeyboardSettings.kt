package dev.zain.znkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.zain.znkeyboard.constants.AgentDefaults
import dev.zain.znkeyboard.constants.ImeColors
import dev.zain.znkeyboard.constants.KeyboardDefaults
import dev.zain.znkeyboard.constants.TranslateLanguage
import dev.zain.znkeyboard.ime.ClipboardHistoryStore
import dev.zain.znkeyboard.ime.PinyinLearningStore
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import dev.zain.znkeyboard.typing.AdaptiveTypingEngine
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeyboardSettings {
    const val MIN_HEIGHT_SCALE = KeyboardDefaults.MIN_HEIGHT_SCALE
    const val MAX_HEIGHT_SCALE = KeyboardDefaults.MAX_HEIGHT_SCALE
    const val DEFAULT_HEIGHT_SCALE = KeyboardDefaults.DEFAULT_HEIGHT_SCALE
    const val MIN_BOTTOM_PADDING_DP = KeyboardDefaults.MIN_BOTTOM_PADDING_DP
    const val MAX_BOTTOM_PADDING_DP = KeyboardDefaults.MAX_BOTTOM_PADDING_DP
    const val DEFAULT_BOTTOM_PADDING_DP = KeyboardDefaults.DEFAULT_BOTTOM_PADDING_DP
    const val DEFAULT_AGENT_API_BASE_URL = AgentDefaults.DEFAULT_API_BASE_URL
    const val DEFAULT_AGENT_MODEL = AgentDefaults.DEFAULT_MODEL
    const val MAX_UPPER_ROW_KEYS = KeyboardDefaults.MAX_UPPER_ROW_KEYS
    private const val LEGACY_MAX_AGENT_ROW_KEYS = 5
    const val MAX_SECOND_ROW_BUTTONS = KeyboardDefaults.MAX_SECOND_ROW_BUTTONS

    private const val BACKUP_FORMAT = "dev.zain.znkeyboard.settings-backup"
    private const val BACKUP_SCHEMA_VERSION = 4
    private const val BACKUP_JSON_INDENT = 2
    private const val PREFS_NAME = "keyboard_settings"
    private const val SECRET_PREFS_NAME = "keyboard_agent_secrets"
    private const val KEY_HEIGHT_SCALE = "height_scale"
    private const val KEY_BOTTOM_PADDING_DP = "bottom_padding_dp"
    private const val KEY_FUNCTION_KEY_BACKGROUNDS_ENABLED = "function_key_backgrounds_enabled"
    private const val KEY_UPPER_ROW_KEYS = "upper_row_keys"
    private const val KEY_AGENT_ROW_KEYS = "agent_row_keys"
    private const val KEY_SECOND_ROW_BUTTONS = "second_row_buttons"
    private const val KEY_KEYBOARD_ROW_ORDER = "keyboard_row_order"
    private const val LEGACY_KEY_SHORTCUT_MACROS = "shortcut_macros"
    // Snippets were removed; these keys only exist to clean up what older versions stored.
    private const val LEGACY_KEY_SAVED_TEXT_PANEL_TAB = "saved_text_panel_tab"
    private const val LEGACY_KEY_TEXT_SNIPPETS = "text_snippets"
    private const val KEY_ADAPTIVE_TOUCH_ENABLED = "adaptive_touch_enabled"
    private const val KEY_KEY_HAPTICS_ENABLED = "key_haptics_enabled"
    private const val KEY_KEY_PREVIEW_ENABLED = "key_preview_enabled"
    private const val KEY_AUTO_CAPITALIZE_ENABLED = "auto_capitalize_enabled"
    private const val KEY_DOUBLE_SPACE_PERIOD_ENABLED = "double_space_period_enabled"
    private const val KEY_PINYIN_MODE_ENABLED = "pinyin_mode_enabled"
    private const val KEY_PINYIN_FUZZY_RULES = "pinyin_fuzzy_rules"
    private const val KEY_PINYIN_LEARNING_ENABLED = "pinyin_learning_enabled"
    private const val KEY_PINYIN_LEARNING_GENERATION = "pinyin_learning_generation"
    private const val KEY_PINYIN_PREDICTIONS_ENABLED = "pinyin_predictions_enabled"
    private const val KEY_UPDATE_AUTO_CHECK = "update_auto_check"
    private const val KEY_UPDATE_LAST_CHECKED_AT = "update_last_checked_at"
    private const val KEY_UPDATE_LATEST_VERSION = "update_latest_version"
    private const val KEY_UPDATE_LATEST_NOTES = "update_latest_notes"
    private const val KEY_UPDATE_LATEST_DOWNLOAD_URL = "update_latest_download_url"
    private const val KEY_UPDATE_LATEST_PAGE_URL = "update_latest_page_url"
    private const val KEY_UPDATE_DISMISSED_VERSION = "update_dismissed_version"
    private const val KEY_KEYBOARD_THEME = "keyboard_theme"
    private const val KEY_TYPING_MODEL_GENERATION = "typing_model_generation"
    private const val KEY_AGENT_MODE_ENABLED = "agent_mode_enabled"
    private const val KEY_AGENT_PROVIDER_TYPE = "agent_provider_type"
    private const val KEY_AGENT_API_BASE_URL = "agent_api_base_url"
    private const val KEY_AGENT_MODEL = "agent_model"
    private const val KEY_GEMINI_MODEL = "gemini_model"
    private const val KEY_AGENT_REASONING_MODE = "agent_reasoning_mode"
    private const val KEY_AGENT_REASONING_TEXT_ENABLED = "agent_reasoning_text_enabled"
    private const val KEY_AGENT_TRANSLATE_TARGET = "agent_translate_target"
    private const val KEY_AGENT_PROVIDERS_MIGRATED = "agent_providers_migrated_v4"
    private const val KEY_DEEPSEEK_MODEL = "deepseek_model"
    private const val KEY_SECOND_ROW_CLIPBOARD_MIGRATED = "second_row_clipboard_migrated"
    private const val KEY_VOICE_INPUT_ENABLED = "voice_input_enabled"
    // Removed providers and profiles; kept only so the migration can read and clear them.
    private const val LEGACY_PROVIDER_OPENROUTER = "openrouter"
    private const val LEGACY_PROVIDER_NANBEIGE = "nanbeige_llama_cpp"
    private const val LEGACY_PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"
    private const val LEGACY_KEY_AGENT_PROVIDERS_MIGRATED_V3 = "agent_providers_migrated_v3"
    private const val LEGACY_KEY_OPENROUTER_PROVIDER_SLUG = "openrouter_provider_slug"
    private const val LEGACY_KEY_AGENT_PROFILES = "agent_profiles"
    private const val LEGACY_KEY_ACTIVE_AGENT_PROFILE_ID = "active_agent_profile_id"
    private const val KEY_AGENT_API_KEY_LEGACY = "agent_api_key"
    private const val KEY_AGENT_API_KEY_CIPHERTEXT = "agent_api_key_ciphertext"
    private const val KEY_AGENT_API_KEY_IV = "agent_api_key_iv"
    private const val KEY_AGENT_API_KEY_LOCKED = "agent_api_key_locked"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_AGENT_API_KEY_ALIAS = "znkeyboard_agent_api_key"
    private const val KEYSTORE_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    val DEFAULT_UPPER_ROW_KEY_IDS = KeyboardDefaults.DEFAULT_UPPER_ROW_KEY_IDS
    val DEFAULT_SECOND_ROW_BUTTON_IDS = KeyboardDefaults.DEFAULT_SECOND_ROW_BUTTON_IDS
    val DEFAULT_KEYBOARD_ROW_ORDER = KeyboardDefaults.DEFAULT_KEYBOARD_ROW_ORDER

    val ACTION_KEY_OPTIONS = listOf(
        UpperRowKeyOption("rewrite", "Rewrite"),
        UpperRowKeyOption("clipboard", "Clipboard"),
    )

    val CONTROL_KEY_OPTIONS = listOf(
        UpperRowKeyOption("ctrl", "Ctrl"),
        UpperRowKeyOption("alt", "Alt"),
        UpperRowKeyOption("tab", "Tab"),
        UpperRowKeyOption("esc", "Esc"),
        UpperRowKeyOption("left", "Left"),
        UpperRowKeyOption("up", "Up"),
        UpperRowKeyOption("down", "Down"),
        UpperRowKeyOption("right", "Right"),
        UpperRowKeyOption("home", "Home"),
        UpperRowKeyOption("end", "End"),
        UpperRowKeyOption("page_up", "PgUp"),
        UpperRowKeyOption("page_down", "PgDn"),
        UpperRowKeyOption("backspace", "Del"),
    )

    /** Every symbol the keyboard can type; the label is also the committed text. */
    val SYMBOL_KEY_OPTIONS = listOf(
        UpperRowKeyOption("backtick", "`"),
        UpperRowKeyOption("tilde", "~"),
        UpperRowKeyOption("bang", "!"),
        UpperRowKeyOption("at", "@"),
        UpperRowKeyOption("hash", "#"),
        UpperRowKeyOption("dollar", "\$"),
        UpperRowKeyOption("percent", "%"),
        UpperRowKeyOption("caret", "^"),
        UpperRowKeyOption("ampersand", "&"),
        UpperRowKeyOption("star", "*"),
        UpperRowKeyOption("left_paren", "("),
        UpperRowKeyOption("right_paren", ")"),
        UpperRowKeyOption("minus", "-"),
        UpperRowKeyOption("underscore", "_"),
        UpperRowKeyOption("equals", "="),
        UpperRowKeyOption("plus", "+"),
        UpperRowKeyOption("left_bracket", "["),
        UpperRowKeyOption("right_bracket", "]"),
        UpperRowKeyOption("left_brace", "{"),
        UpperRowKeyOption("right_brace", "}"),
        UpperRowKeyOption("backslash", "\\"),
        UpperRowKeyOption("pipe", "|"),
        UpperRowKeyOption("semicolon", ";"),
        UpperRowKeyOption("colon", ":"),
        UpperRowKeyOption("apostrophe", "'"),
        UpperRowKeyOption("quote", "\""),
        UpperRowKeyOption("comma", ","),
        UpperRowKeyOption("period", "."),
        UpperRowKeyOption("less_than", "<"),
        UpperRowKeyOption("greater_than", ">"),
        UpperRowKeyOption("slash", "/"),
        UpperRowKeyOption("question", "?"),
        UpperRowKeyOption("euro", "€"),
        UpperRowKeyOption("pound", "£"),
        UpperRowKeyOption("yen", "¥"),
        UpperRowKeyOption("bullet", "•"),
    )

    val SHORTCUT_ROW_KEY_OPTIONS = ACTION_KEY_OPTIONS + CONTROL_KEY_OPTIONS + SYMBOL_KEY_OPTIONS
    val UPPER_ROW_KEY_OPTIONS = SHORTCUT_ROW_KEY_OPTIONS
    val SECOND_ROW_BUTTON_OPTIONS = SHORTCUT_ROW_KEY_OPTIONS
    private val shortcutRowKeyOptionIds = SHORTCUT_ROW_KEY_OPTIONS.mapTo(mutableSetOf()) { it.id }
    private val upperRowKeyOptionIds = shortcutRowKeyOptionIds
    private val symbolTextByKeyId = SYMBOL_KEY_OPTIONS.associate { it.id to it.label }
    private val keyboardRowIds = KeyboardRow.entries.mapTo(mutableSetOf()) { it.id }
    private val whitespaceRegex = Regex("\\s+")

    fun readHeightScale(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs
            .getFloat(KEY_HEIGHT_SCALE, DEFAULT_HEIGHT_SCALE)
            .coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
    }

    fun saveHeightScale(context: Context, scale: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_HEIGHT_SCALE, scale.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)).apply()
    }

    fun readBottomPaddingDp(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return normalizeBottomPaddingDp(
            prefs.getInt(KEY_BOTTOM_PADDING_DP, DEFAULT_BOTTOM_PADDING_DP),
        )
    }

    fun saveBottomPaddingDp(context: Context, paddingDp: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_BOTTOM_PADDING_DP, normalizeBottomPaddingDp(paddingDp)).apply()
    }

    fun normalizeBottomPaddingDp(paddingDp: Int): Int {
        return paddingDp.coerceIn(MIN_BOTTOM_PADDING_DP, MAX_BOTTOM_PADDING_DP)
    }

    private fun readFlag(context: Context, key: String, default: Boolean): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, default)

    private fun saveFlag(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    }

    /** Personal touch model + letter prior decoding. */
    fun readAdaptiveTouchEnabled(context: Context) = readFlag(context, KEY_ADAPTIVE_TOUCH_ENABLED, true)
    fun saveAdaptiveTouchEnabled(context: Context, enabled: Boolean) = saveFlag(context, KEY_ADAPTIVE_TOUCH_ENABLED, enabled)
    fun readKeyHapticsEnabled(context: Context) = readFlag(context, KEY_KEY_HAPTICS_ENABLED, true)
    fun saveKeyHapticsEnabled(context: Context, enabled: Boolean) = saveFlag(context, KEY_KEY_HAPTICS_ENABLED, enabled)
    fun readKeyPreviewEnabled(context: Context) = readFlag(context, KEY_KEY_PREVIEW_ENABLED, true)
    fun saveKeyPreviewEnabled(context: Context, enabled: Boolean) = saveFlag(context, KEY_KEY_PREVIEW_ENABLED, enabled)
    fun readAutoCapitalizeEnabled(context: Context) = readFlag(context, KEY_AUTO_CAPITALIZE_ENABLED, true)
    fun saveAutoCapitalizeEnabled(context: Context, enabled: Boolean) = saveFlag(context, KEY_AUTO_CAPITALIZE_ENABLED, enabled)
    fun readDoubleSpacePeriodEnabled(context: Context) = readFlag(context, KEY_DOUBLE_SPACE_PERIOD_ENABLED, true)
    fun saveDoubleSpacePeriodEnabled(context: Context, enabled: Boolean) =
        saveFlag(context, KEY_DOUBLE_SPACE_PERIOD_ENABLED, enabled)

    /** Whether letters go to the pinyin composer (中) instead of straight into the field (EN). */
    fun readPinyinModeEnabled(context: Context) = readFlag(context, KEY_PINYIN_MODE_ENABLED, false)
    fun savePinyinModeEnabled(context: Context, enabled: Boolean) =
        saveFlag(context, KEY_PINYIN_MODE_ENABLED, enabled)

    fun readPinyinLearningEnabled(context: Context) = readFlag(context, KEY_PINYIN_LEARNING_ENABLED, true)
    fun savePinyinLearningEnabled(context: Context, enabled: Boolean) =
        saveFlag(context, KEY_PINYIN_LEARNING_ENABLED, enabled)

    /** Whether the candidate bar suggests what comes next after a pinyin commit. */
    fun readPinyinPredictionsEnabled(context: Context) = readFlag(context, KEY_PINYIN_PREDICTIONS_ENABLED, true)
    fun savePinyinPredictionsEnabled(context: Context, enabled: Boolean) =
        saveFlag(context, KEY_PINYIN_PREDICTIONS_ENABLED, enabled)

    /** Off by default: checking contacts GitHub, so the user opts in. */
    fun readUpdateAutoCheck(context: Context) = readFlag(context, KEY_UPDATE_AUTO_CHECK, false)
    fun saveUpdateAutoCheck(context: Context, enabled: Boolean) = saveFlag(context, KEY_UPDATE_AUTO_CHECK, enabled)

    /** When GitHub last answered a check, or 0 if never. */
    fun readUpdateLastCheckedAt(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_UPDATE_LAST_CHECKED_AT, 0L)

    /** The latest release from the last successful check, kept so the banner shows without checking again. */
    fun readLatestRelease(context: Context): UpdateChecker.Release? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_UPDATE_LATEST_VERSION, null) ?: return null
        return UpdateChecker.Release(
            version = version,
            notes = prefs.getString(KEY_UPDATE_LATEST_NOTES, null).orEmpty(),
            downloadUrl = prefs.getString(KEY_UPDATE_LATEST_DOWNLOAD_URL, null) ?: return null,
            pageUrl = prefs.getString(KEY_UPDATE_LATEST_PAGE_URL, null) ?: return null,
        )
    }

    fun saveLatestRelease(context: Context, release: UpdateChecker.Release, checkedAt: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_UPDATE_LAST_CHECKED_AT, checkedAt)
            .putString(KEY_UPDATE_LATEST_VERSION, release.version)
            .putString(KEY_UPDATE_LATEST_NOTES, release.notes)
            .putString(KEY_UPDATE_LATEST_DOWNLOAD_URL, release.downloadUrl)
            .putString(KEY_UPDATE_LATEST_PAGE_URL, release.pageUrl)
            .apply()
    }

    /** The version whose banner the user closed; a newer release shows the banner again. */
    fun readDismissedUpdateVersion(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_UPDATE_DISMISSED_VERSION, null)

    fun saveDismissedUpdateVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_UPDATE_DISMISSED_VERSION, version)
            .apply()
    }

    /** Bumped when settings clear or restore learned pinyin, so the keyboard reloads it. */
    fun readPinyinLearningGeneration(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_PINYIN_LEARNING_GENERATION, 0)

    fun bumpPinyinLearningGeneration(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_PINYIN_LEARNING_GENERATION, prefs.getInt(KEY_PINYIN_LEARNING_GENERATION, 0) + 1).commit()
    }

    fun readPinyinFuzzyRules(context: Context): Set<PinyinFuzzyRule> {
        val ids = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PINYIN_FUZZY_RULES, null)
            ?: return PinyinFuzzyRule.DEFAULTS
        return PinyinFuzzyRule.fromIds(ids)
    }

    fun savePinyinFuzzyRules(context: Context, rules: Set<PinyinFuzzyRule>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PINYIN_FUZZY_RULES, rules.mapTo(mutableSetOf()) { it.id })
            .apply()
    }

    /** Drops snippet data saved by versions that still had snippets. */
    fun deleteLegacySnippetData(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_KEY_TEXT_SNIPPETS)
            .remove(LEGACY_KEY_SAVED_TEXT_PANEL_TAB)
            .apply()
    }

    /** Macros were removed; forget saved ones. Their keys drop out of the rows on the next read. */
    fun deleteLegacyMacroData(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_KEY_SHORTCUT_MACROS)
            .apply()
    }

    fun readKeyboardTheme(context: Context): ImeColors.Style = ImeColors.Style.fromId(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_KEYBOARD_THEME, null),
    )

    fun saveKeyboardTheme(context: Context, style: ImeColors.Style) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_KEYBOARD_THEME, style.id).apply()
    }

    /** Bumped when the user resets learned typing data; the running keyboard drops its in-memory copy. */
    fun readTypingModelGeneration(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_TYPING_MODEL_GENERATION, 0)

    fun bumpTypingModelGeneration(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_TYPING_MODEL_GENERATION, prefs.getInt(KEY_TYPING_MODEL_GENERATION, 0) + 1).commit()
    }

    fun readFunctionKeyBackgroundsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FUNCTION_KEY_BACKGROUNDS_ENABLED, true)
    }

    fun saveFunctionKeyBackgroundsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FUNCTION_KEY_BACKGROUNDS_ENABLED, enabled)
            .apply()
    }

    fun readUpperRowKeyIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_UPPER_ROW_KEYS, null) ?: return DEFAULT_UPPER_ROW_KEY_IDS
        return parseUpperRowKeyIds(stored) ?: DEFAULT_UPPER_ROW_KEY_IDS
    }

    fun saveUpperRowKeyIds(context: Context, keyIds: List<String>) {
        val keys = normalizeUpperRowKeyIds(keyIds)
        val encoded = JSONArray().apply {
            keys.forEach(::put)
        }.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_UPPER_ROW_KEYS, encoded).apply()
    }

    fun normalizeUpperRowKeyIds(keyIds: List<String>): List<String> {
        return keyIds
            .mapNotNull(::normalizeShortcutRowKeyId)
            .take(MAX_UPPER_ROW_KEYS)
    }

    private fun normalizeShortcutRowKeyId(keyId: String): String? = keyId.takeIf { it in shortcutRowKeyOptionIds }

    /** Text typed by a symbol shortcut key, or null when [keyId] is not a symbol. */
    fun symbolForShortcutRowKey(keyId: String): String? = symbolTextByKeyId[keyId]

    fun labelForUpperRowKey(keyId: String): String {
        return UPPER_ROW_KEY_OPTIONS.firstOrNull { it.id == keyId }?.label.orEmpty()
    }

    fun readSecondRowButtonIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SECOND_ROW_BUTTONS, null)?.let { stored ->
            return parseSecondRowButtonIds(stored) ?: DEFAULT_SECOND_ROW_BUTTON_IDS
        }

        val legacyAgentRow = prefs.getString(KEY_AGENT_ROW_KEYS, null)
            ?.let(::parseAgentRowKeyIds)
            ?: return DEFAULT_SECOND_ROW_BUTTON_IDS
        return normalizeSecondRowButtonIds(listOf("rewrite") + legacyAgentRow)
    }

    /**
     * One-time move of the clipboard from long-press space (now voice input) to the shortcut row:
     * a saved row gets the clipboard key where @ was, and # makes room for it.
     */
    fun migrateSecondRowClipboard(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SECOND_ROW_CLIPBOARD_MIGRATED, false)) return
        if (prefs.contains(KEY_SECOND_ROW_BUTTONS)) {
            val buttons = readSecondRowButtonIds(context).toMutableList()
            if ("clipboard" !in buttons) {
                buttons.remove("hash")
                val index = buttons.indexOf("at").takeIf { it >= 0 }
                    ?: (buttons.indexOf("rewrite") + 1)
                buttons.add(index, "clipboard")
                saveSecondRowButtonIds(context, buttons)
            }
        }
        prefs.edit().putBoolean(KEY_SECOND_ROW_CLIPBOARD_MIGRATED, true).apply()
    }

    fun readVoiceInputEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_INPUT_ENABLED, true)
    }

    fun saveVoiceInputEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOICE_INPUT_ENABLED, enabled)
            .apply()
    }

    fun saveSecondRowButtonIds(context: Context, buttonIds: List<String>) {
        val buttons = normalizeSecondRowButtonIds(buttonIds)
        val encoded = JSONArray().apply {
            buttons.forEach(::put)
        }.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SECOND_ROW_BUTTONS, encoded).apply()
    }

    fun normalizeSecondRowButtonIds(buttonIds: List<String>): List<String> {
        return buttonIds
            .mapNotNull(::normalizeShortcutRowKeyId)
            .distinct()
            .take(MAX_SECOND_ROW_BUTTONS)
    }

    fun labelForSecondRowButton(buttonId: String): String {
        return SECOND_ROW_BUTTON_OPTIONS.firstOrNull { it.id == buttonId }?.label.orEmpty()
    }

    fun labelForShortcutRowKey(keyId: String): String {
        return SHORTCUT_ROW_KEY_OPTIONS.firstOrNull { it.id == keyId }?.label.orEmpty()
    }

    fun readKeyboardRowOrder(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_KEYBOARD_ROW_ORDER, null) ?: return DEFAULT_KEYBOARD_ROW_ORDER
        return parseKeyboardRowOrder(stored) ?: DEFAULT_KEYBOARD_ROW_ORDER
    }

    fun saveKeyboardRowOrder(context: Context, rowIds: List<String>) {
        val rows = normalizeKeyboardRowOrder(rowIds)
        val encoded = JSONArray().apply {
            rows.forEach(::put)
        }.toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_KEYBOARD_ROW_ORDER, encoded).apply()
    }

    fun normalizeKeyboardRowOrder(rowIds: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return buildList {
            rowIds.forEach { rowId ->
                if (rowId in keyboardRowIds && seen.add(rowId)) {
                    add(rowId)
                }
            }
            DEFAULT_KEYBOARD_ROW_ORDER.forEach { rowId ->
                if (seen.add(rowId)) {
                    add(rowId)
                }
            }
        }
    }

    fun labelForKeyboardRow(rowId: String): String {
        return KeyboardRow.entries.firstOrNull { it.id == rowId }?.label.orEmpty()
    }

    fun readAgentModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGENT_MODE_ENABLED, false)
    }

    fun saveAgentModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGENT_MODE_ENABLED, enabled)
            .apply()
    }

    fun readAgentProviderType(context: Context): AgentProviderType {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_PROVIDER_TYPE, null)
        return AgentProviderType.entries.firstOrNull { it.id == stored } ?: AgentProviderType.Gemini
    }

    fun saveAgentProviderType(context: Context, providerType: AgentProviderType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_PROVIDER_TYPE, providerType.id)
            .apply()
    }

    fun readAgentApiBaseUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_API_BASE_URL, DEFAULT_AGENT_API_BASE_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AGENT_API_BASE_URL
    }

    fun saveAgentApiBaseUrl(context: Context, baseUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_API_BASE_URL, baseUrl.trim())
            .apply()
    }

    /** Each provider keeps its own model, so switching back restores it. */
    fun readAgentModel(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
    ): String {
        val (key, default) = modelKeyAndDefault(providerType)
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, default)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return default
        // Gemini is limited to Flash-Lite; a larger model saved earlier or restored from a backup
        // falls back to the default instead of spending far more per request. DeepSeek is fixed.
        return when (providerType) {
            AgentProviderType.Gemini -> if (isGeminiLiteModel(stored)) stored else default
            AgentProviderType.DeepSeek -> default
        }
    }

    /** Gemini Flash-Lite 2.5 or newer, e.g. gemini-3.5-flash-lite or gemini-4-flash-lite-preview. */
    fun isGeminiLiteModel(modelId: String): Boolean {
        val match = GEMINI_LITE_MODEL.matchEntire(modelId.trim().lowercase(java.util.Locale.US)) ?: return false
        val version = match.groupValues[1].toDoubleOrNull() ?: return false
        return version >= MIN_GEMINI_LITE_VERSION
    }

    private val GEMINI_LITE_MODEL = Regex("""gemini-(\d+(?:\.\d+)?)-flash-lite(?:-[a-z0-9-]+)?""")
    private const val MIN_GEMINI_LITE_VERSION = 2.5

    fun saveAgentModel(
        context: Context,
        model: String,
        providerType: AgentProviderType = readAgentProviderType(context),
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(modelKeyAndDefault(providerType).first, model.trim())
            .apply()
    }

    private fun modelKeyAndDefault(providerType: AgentProviderType): Pair<String, String> = when (providerType) {
        AgentProviderType.Gemini -> KEY_GEMINI_MODEL to AgentDefaults.DEFAULT_GEMINI_MODEL
        AgentProviderType.DeepSeek -> KEY_DEEPSEEK_MODEL to AgentDefaults.DEFAULT_DEEPSEEK_MODEL
    }

    /**
     * One-time move to the Gemini / DeepSeek provider list. A custom OpenAI-compatible setup pointed
     * at Gemini or DeepSeek becomes that provider, key included; any other custom endpoint, and the
     * older OpenRouter and Nanbeige setups, fall back to Gemini. Profiles are dropped.
     */
    fun migrateAgentProviders(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AGENT_PROVIDERS_MIGRATED, false)) return
        val secrets = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)

        fun moveSecret(fromProviderId: String, to: AgentProviderType) {
            val ciphertext = secrets.getString("${fromProviderId}_$KEY_AGENT_API_KEY_CIPHERTEXT", null)
            val iv = secrets.getString("${fromProviderId}_$KEY_AGENT_API_KEY_IV", null)
            if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return
            if (readAgentApiKey(context, to).isNotBlank()) return
            val apiKey = decryptAgentApiKey(ciphertext, iv).getOrNull().orEmpty()
            if (apiKey.isBlank()) return
            saveAgentApiKey(context, to, apiKey)
            saveAgentApiKeyLocked(context, to, secrets.getBoolean("${fromProviderId}_$KEY_AGENT_API_KEY_LOCKED", false))
        }

        // Before the v3 migration, versions defaulted to OpenAI compatible without storing it; an
        // existing setup with no stored provider is that default, while a fresh install has none.
        val hasCompatibleSetup = prefs.contains(KEY_AGENT_API_BASE_URL) ||
            secrets.contains("${LEGACY_PROVIDER_OPENAI_COMPATIBLE}_$KEY_AGENT_API_KEY_CIPHERTEXT")
        val storedProvider = prefs.getString(KEY_AGENT_PROVIDER_TYPE, null)
            ?: LEGACY_PROVIDER_OPENAI_COMPATIBLE.takeIf {
                hasCompatibleSetup && !prefs.getBoolean(LEGACY_KEY_AGENT_PROVIDERS_MIGRATED_V3, false)
            }
        when (val stored = storedProvider) {
            LEGACY_PROVIDER_OPENAI_COMPATIBLE -> {
                val baseUrl = readAgentApiBaseUrl(context)
                when {
                    GEMINI_HOST in baseUrl -> {
                        moveSecret(stored, AgentProviderType.Gemini)
                        prefs.getString(KEY_AGENT_MODEL, null)?.let { saveAgentModel(context, it, AgentProviderType.Gemini) }
                        saveAgentProviderType(context, AgentProviderType.Gemini)
                    }
                    DEEPSEEK_HOST in baseUrl -> {
                        moveSecret(stored, AgentProviderType.DeepSeek)
                        saveAgentProviderType(context, AgentProviderType.DeepSeek)
                    }
                    else -> saveAgentProviderType(context, AgentProviderType.Gemini)
                }
            }
            LEGACY_PROVIDER_OPENROUTER, LEGACY_PROVIDER_NANBEIGE -> saveAgentProviderType(context, AgentProviderType.Gemini)
        }

        val staleSecretKeys = secrets.all.keys.filter {
            it.startsWith("profile_") ||
                it.startsWith("${LEGACY_PROVIDER_OPENROUTER}_") ||
                it.startsWith("${LEGACY_PROVIDER_NANBEIGE}_") ||
                it.startsWith("${LEGACY_PROVIDER_OPENAI_COMPATIBLE}_")
        }
        secrets.edit().apply { staleSecretKeys.forEach(::remove) }.apply()
        prefs.edit()
            .remove(LEGACY_KEY_OPENROUTER_PROVIDER_SLUG)
            .remove(LEGACY_KEY_AGENT_PROFILES)
            .remove(LEGACY_KEY_ACTIVE_AGENT_PROFILE_ID)
            .remove(LEGACY_KEY_AGENT_PROVIDERS_MIGRATED_V3)
            .putBoolean(KEY_AGENT_PROVIDERS_MIGRATED, true)
            .apply()
    }

    /** Language Rewrite translates into while typing Chinese (pinyin mode). Stored as its code. */
    fun readAgentTranslateTarget(context: Context): TranslateLanguage {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_TRANSLATE_TARGET, null)
        // Earlier builds stored the full name ("English").
        return AgentDefaults.TRANSLATE_LANGUAGES.firstOrNull { it.code == stored || it.name == stored }
            ?: AgentDefaults.DEFAULT_TRANSLATE_LANGUAGE
    }

    fun saveAgentTranslateTarget(context: Context, code: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_TRANSLATE_TARGET, code)
            .apply()
    }

    fun readAgentReasoningMode(context: Context): AgentReasoningMode {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AGENT_REASONING_MODE, null)
        return AgentReasoningMode.entries.firstOrNull { it.id == stored } ?: AgentReasoningMode.Off
    }

    fun saveAgentReasoningMode(context: Context, reasoningMode: AgentReasoningMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_REASONING_MODE, reasoningMode.id)
            .apply()
    }

    fun readAgentReasoningTextEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGENT_REASONING_TEXT_ENABLED, false)
    }

    fun saveAgentReasoningTextEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGENT_REASONING_TEXT_ENABLED, enabled)
            .apply()
    }

    fun readAgentApiKey(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
    ): String {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(providerSecretKey(providerType, KEY_AGENT_API_KEY_CIPHERTEXT), null)
        val iv = prefs.getString(providerSecretKey(providerType, KEY_AGENT_API_KEY_IV), null)
        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return decryptAgentApiKey(ciphertext, iv).getOrElse { "" }
        }

        return migrateLegacyAgentApiKey(prefs, providerType)
    }

    fun saveAgentApiKey(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
        apiKey: String,
    ) {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val trimmedApiKey = apiKey.trim()
        val ciphertextKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_CIPHERTEXT)
        val ivKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_IV)
        val lockedKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED)
        if (trimmedApiKey.isBlank()) {
            prefs.edit()
                .remove(ciphertextKey)
                .remove(ivKey)
                .remove(lockedKey)
                .remove(KEY_AGENT_API_KEY_LEGACY)
                .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                .remove(KEY_AGENT_API_KEY_IV)
                .remove(KEY_AGENT_API_KEY_LOCKED)
                .apply()
            return
        }

        encryptAgentApiKey(trimmedApiKey)
            .onSuccess { encryptedValue ->
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .putString(ciphertextKey, encryptedValue.ciphertext)
                    .putString(ivKey, encryptedValue.iv)
                    .apply()
            }
            .onFailure {
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .remove(ciphertextKey)
                    .remove(ivKey)
                    .remove(lockedKey)
                    .apply()
            }
    }

    fun readAgentApiKeyLocked(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
    ): Boolean {
        val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        val lockedKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED)
        if (prefs.contains(lockedKey)) {
            return prefs.getBoolean(lockedKey, false)
        }
        if (prefs.contains(KEY_AGENT_API_KEY_LOCKED) && readLegacyAgentApiKey(prefs).isNotBlank()) {
            val legacyLocked = prefs.getBoolean(KEY_AGENT_API_KEY_LOCKED, false)
            migrateLegacyAgentApiKey(prefs, providerType)
            return legacyLocked
        }
        return false
    }

    fun saveAgentApiKeyLocked(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
        locked: Boolean,
    ) {
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED), locked)
            .remove(KEY_AGENT_API_KEY_LOCKED)
            .apply()
    }

    /** The selected provider by default; voice input asks for Gemini's settings whichever is selected. */
    fun readAgentProviderSettings(
        context: Context,
        providerType: AgentProviderType = readAgentProviderType(context),
    ): AgentProviderSettings {
        return AgentProviderSettings(
            providerType = providerType,
            baseUrl = providerType.baseUrl,
            model = readAgentModel(context, providerType),
            apiKey = readAgentApiKey(context, providerType),
            // Both providers rewrite fastest, and most reliably, with reasoning off.
            reasoningMode = AgentReasoningMode.Off,
            reasoningTextEnabled = readAgentReasoningTextEnabled(context),
        )
    }

    /**
     * Creates the backup payload that is encrypted before it leaves the device.
     * Never write this JSON directly to user-selected storage.
     */
    fun createBackupPayloadJson(context: Context): String {
        val backup = JSONObject()
            .put("format", BACKUP_FORMAT)
            .put("schemaVersion", BACKUP_SCHEMA_VERSION)
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("appId", context.packageName)
            .put(
                "settings",
                JSONObject()
                    .put(
                        "keyboard",
                        JSONObject()
                            .put("heightScale", readHeightScale(context))
                            .put("bottomPaddingDp", readBottomPaddingDp(context))
                            .put("theme", readKeyboardTheme(context).id)
                            .put("adaptiveTouch", readAdaptiveTouchEnabled(context))
                            .put("keyHaptics", readKeyHapticsEnabled(context))
                            .put("keyPreview", readKeyPreviewEnabled(context))
                            .put("autoCapitalize", readAutoCapitalizeEnabled(context))
                            .put("doubleSpacePeriod", readDoubleSpacePeriodEnabled(context))
                            .put("functionKeyBackgroundsEnabled", readFunctionKeyBackgroundsEnabled(context))
                            .put("shortcutRowAKeys", stringArrayJson(readUpperRowKeyIds(context)))
                            .put("shortcutRowBKeys", stringArrayJson(readSecondRowButtonIds(context)))
                            .put("shortcutRowOrder", stringArrayJson(readKeyboardRowOrder(context))),
                    )
                    .put(
                        "rewrite",
                        JSONObject()
                            .put("enabled", readAgentModeEnabled(context))
                            .put("providerType", readAgentProviderType(context).id)
                            .put("apiBaseUrl", readAgentApiBaseUrl(context))
                            .put("model", readAgentModel(context, AgentProviderType.DeepSeek))
                            .put("geminiModel", readAgentModel(context, AgentProviderType.Gemini))
                            .put("reasoningMode", readAgentReasoningMode(context).id)
                            .put("reasoningTextEnabled", readAgentReasoningTextEnabled(context)),
                    )
                    .put("clipboardHistory", clipboardHistoryJson(ClipboardHistoryStore.read(context)))
                    .put("learnedTyping", learnedTypingJson(AdaptiveTypingEngine.readLearnedData(context)))
                    .apply {
                        PinyinLearningStore.readBytes(context)?.let {
                            put("learnedPinyin", Base64.encodeToString(it, Base64.NO_WRAP))
                        }
                    },
            )
            .put(
                "secrets",
                JSONObject()
                    .put(
                        "agentApiKeys",
                        JSONObject().apply {
                            AgentProviderType.entries.forEach { providerType ->
                                put(
                                    providerType.id,
                                    JSONObject()
                                        .put("value", readAgentApiKey(context, providerType))
                                        .put("locked", readAgentApiKeyLocked(context, providerType)),
                                )
                            }
                        },
                    ),
            )

        return backup.toString(BACKUP_JSON_INDENT)
    }

    fun restoreBackupPayloadJson(context: Context, backupJson: String): BackupRestoreResult {
        val backup = JSONObject(backupJson)
        if (backup.optString("format") != BACKUP_FORMAT) {
            throw JSONException("Not a ZnKeyboard settings backup.")
        }

        val schemaVersion = backup.optInt("schemaVersion", -1)
        if (schemaVersion !in 1..BACKUP_SCHEMA_VERSION) {
            throw JSONException("Unsupported ZnKeyboard backup version.")
        }

        val settings = backup.optJSONObject("settings")
            ?: throw JSONException("Backup is missing settings.")
        val keyboard = settings.optJSONObject("keyboard") ?: JSONObject()
        val rewrite = settings.optJSONObject("rewrite") ?: JSONObject()
        val secrets = backup.optJSONObject("secrets")

        val heightScale = keyboard.optFiniteFloat("heightScale", readHeightScale(context))
            .coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
        val bottomPaddingDp = normalizeBottomPaddingDp(
            keyboard.optInt("bottomPaddingDp", readBottomPaddingDp(context)),
        )
        val theme = ImeColors.Style.fromId(keyboard.optString("theme", readKeyboardTheme(context).id))
        val adaptiveTouch = keyboard.optBoolean("adaptiveTouch", readAdaptiveTouchEnabled(context))
        val keyHaptics = keyboard.optBoolean("keyHaptics", readKeyHapticsEnabled(context))
        val keyPreview = keyboard.optBoolean("keyPreview", readKeyPreviewEnabled(context))
        val autoCapitalize = keyboard.optBoolean("autoCapitalize", readAutoCapitalizeEnabled(context))
        val doubleSpacePeriod = keyboard.optBoolean("doubleSpacePeriod", readDoubleSpacePeriodEnabled(context))
        val functionKeyBackgroundsEnabled = keyboard.optBoolean(
            "functionKeyBackgroundsEnabled",
            readFunctionKeyBackgroundsEnabled(context),
        )
        val upperRowKeys = keyboard.optStringList("shortcutRowAKeys")
            ?.let(::normalizeUpperRowKeyIds)
            ?: readUpperRowKeyIds(context)
        val secondRowButtons = keyboard.optStringList("shortcutRowBKeys")
            ?.let(::normalizeSecondRowButtonIds)
            ?: readSecondRowButtonIds(context)
        val rowOrder = keyboard.optStringList("shortcutRowOrder")
            ?.let(::normalizeKeyboardRowOrder)
            ?: readKeyboardRowOrder(context)

        val clipboardHistory = settings.optClipboardHistory("clipboardHistory")
            ?.let(ClipboardHistoryStore::normalizeEntries)
        val learnedTyping = settings.optJSONObject("learnedTyping")?.let { typing ->
            AdaptiveTypingEngine.LearnedData(
                portraitTouch = typing.optBase64("portraitTouch"),
                landscapeTouch = typing.optBase64("landscapeTouch"),
                personalLetters = typing.optBase64("personalLetters"),
            )
        }

        val learnedPinyin = settings.optBase64("learnedPinyin")

        val agentSecrets = secrets?.optJSONObject("agentApiKeys")
        val restoredAgentSecrets = AgentProviderType.entries.mapNotNull { providerType ->
            val secret = agentSecrets?.optJSONObject(providerType.id) ?: return@mapNotNull null
            RestoredSecret(
                providerType = providerType,
                value = secret.optString("value"),
                locked = secret.optBoolean(
                    "locked",
                    readAgentApiKeyLocked(context, providerType),
                ),
            )
        }
        val providerType = rewrite.optById(
            key = "providerType",
            entries = AgentProviderType.entries,
            defaultValue = readAgentProviderType(context),
            idFor = AgentProviderType::id,
        )
        val reasoningMode = rewrite.optById(
            key = "reasoningMode",
            entries = AgentReasoningMode.entries,
            defaultValue = readAgentReasoningMode(context),
            idFor = AgentReasoningMode::id,
        )

        saveHeightScale(context, heightScale)
        saveBottomPaddingDp(context, bottomPaddingDp)
        saveKeyboardTheme(context, theme)
        saveAdaptiveTouchEnabled(context, adaptiveTouch)
        saveKeyHapticsEnabled(context, keyHaptics)
        saveKeyPreviewEnabled(context, keyPreview)
        saveAutoCapitalizeEnabled(context, autoCapitalize)
        saveDoubleSpacePeriodEnabled(context, doubleSpacePeriod)
        saveFunctionKeyBackgroundsEnabled(context, functionKeyBackgroundsEnabled)
        saveUpperRowKeyIds(context, upperRowKeys)
        saveSecondRowButtonIds(context, secondRowButtons)
        saveKeyboardRowOrder(context, rowOrder)
        saveAgentModeEnabled(context, rewrite.optBoolean("enabled", readAgentModeEnabled(context)))
        saveAgentProviderType(context, providerType)
        saveAgentApiBaseUrl(context, rewrite.optString("apiBaseUrl", readAgentApiBaseUrl(context)))
        saveAgentModel(
            context,
            rewrite.optString("model", readAgentModel(context, AgentProviderType.DeepSeek)),
            AgentProviderType.DeepSeek,
        )
        saveAgentModel(
            context,
            rewrite.optString("geminiModel", readAgentModel(context, AgentProviderType.Gemini)),
            AgentProviderType.Gemini,
        )
        saveAgentReasoningMode(context, reasoningMode)
        saveAgentReasoningTextEnabled(
            context,
            rewrite.optBoolean("reasoningTextEnabled", readAgentReasoningTextEnabled(context)),
        )
        clipboardHistory?.let { ClipboardHistoryStore.replace(context, it) }
        learnedTyping?.let {
            AdaptiveTypingEngine.replaceLearnedData(context, it)
            bumpTypingModelGeneration(context)
        }
        learnedPinyin?.let {
            PinyinLearningStore.replace(context, it)
            bumpPinyinLearningGeneration(context)
        }
        restoredAgentSecrets.forEach { secret ->
            saveAgentApiKey(context, secret.providerType, secret.value)
            saveAgentApiKeyLocked(context, secret.providerType, secret.locked)
        }

        return BackupRestoreResult(
            schemaVersion = schemaVersion,
            clipboardEntryCount = clipboardHistory?.size ?: ClipboardHistoryStore.read(context).size,
        )
    }

    private fun parseUpperRowKeyIds(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeUpperRowKeyIds)
    }

    private fun parseAgentRowKeyIds(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeLegacyAgentRowKeyIds)
    }

    private fun normalizeLegacyAgentRowKeyIds(keyIds: List<String>): List<String> {
        return keyIds
            .filter { it in upperRowKeyOptionIds }
            .take(LEGACY_MAX_AGENT_ROW_KEYS)
    }

    private fun parseSecondRowButtonIds(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeSecondRowButtonIds)
    }

    private fun parseKeyboardRowOrder(stored: String): List<String>? {
        return parseStringArray(stored)?.let(::normalizeKeyboardRowOrder)
    }

    private fun parseStringArray(stored: String): List<String>? {
        return try {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun encryptAgentApiKey(apiKey: String): Result<EncryptedValue> {
        return runCatching {
            val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAgentSecretKey())
            EncryptedValue(
                ciphertext = Base64.encodeToString(cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            )
        }
    }

    private fun decryptAgentApiKey(ciphertext: String, iv: String): Result<String> {
        return runCatching {
            val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
            val spec = GCMParameterSpec(
                GCM_TAG_LENGTH_BITS,
                Base64.decode(iv, Base64.NO_WRAP),
            )
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateAgentSecretKey(), spec)
            val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            plaintext.toString(Charsets.UTF_8)
        }
    }

    private fun migrateLegacyAgentApiKey(
        prefs: SharedPreferences,
        providerType: AgentProviderType,
    ): String {
        val legacyApiKey = readLegacyAgentApiKey(prefs)
        if (legacyApiKey.isBlank()) return ""

        val locked = prefs.getBoolean(KEY_AGENT_API_KEY_LOCKED, false)
        val ciphertextKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_CIPHERTEXT)
        val ivKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_IV)
        val lockedKey = providerSecretKey(providerType, KEY_AGENT_API_KEY_LOCKED)
        encryptAgentApiKey(legacyApiKey)
            .onSuccess { encryptedValue ->
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .putString(ciphertextKey, encryptedValue.ciphertext)
                    .putString(ivKey, encryptedValue.iv)
                    .putBoolean(lockedKey, locked)
                    .apply()
            }
            .onFailure {
                prefs.edit()
                    .remove(KEY_AGENT_API_KEY_LEGACY)
                    .remove(KEY_AGENT_API_KEY_CIPHERTEXT)
                    .remove(KEY_AGENT_API_KEY_IV)
                    .remove(KEY_AGENT_API_KEY_LOCKED)
                    .remove(ciphertextKey)
                    .remove(ivKey)
                    .remove(lockedKey)
                    .apply()
            }
        return legacyApiKey
    }

    private fun readLegacyAgentApiKey(prefs: SharedPreferences): String {
        val ciphertext = prefs.getString(KEY_AGENT_API_KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_AGENT_API_KEY_IV, null)
        if (!ciphertext.isNullOrBlank() && !iv.isNullOrBlank()) {
            return decryptAgentApiKey(ciphertext, iv).getOrElse { "" }
        }
        return prefs.getString(KEY_AGENT_API_KEY_LEGACY, "").orEmpty().trim()
    }

    private const val GEMINI_HOST = "generativelanguage.googleapis.com"
    private const val DEEPSEEK_HOST = "api.deepseek.com"

    private fun providerSecretKey(providerType: AgentProviderType, key: String): String {
        return "${providerType.id}_$key"
    }

    private fun stringArrayJson(values: List<String>): JSONArray {
        return JSONArray().apply {
            values.forEach(::put)
        }
    }

    private fun clipboardHistoryJson(entries: List<ClipboardHistoryStore.Entry>): JSONArray {
        return JSONArray().apply {
            ClipboardHistoryStore.normalizeEntries(entries).forEach { entry ->
                put(
                    JSONObject()
                        .put("text", entry.text)
                        .put("timestampMillis", entry.timestampMillis)
                        .apply {
                            entry.sourcePackageName?.let { put("sourcePackageName", it) }
                            entry.sourceAppLabel?.let { put("sourceAppLabel", it) }
                        },
                )
            }
        }
    }

    private fun learnedTypingJson(data: AdaptiveTypingEngine.LearnedData): JSONObject {
        return JSONObject().apply {
            data.portraitTouch?.let { put("portraitTouch", Base64.encodeToString(it, Base64.NO_WRAP)) }
            data.landscapeTouch?.let { put("landscapeTouch", Base64.encodeToString(it, Base64.NO_WRAP)) }
            data.personalLetters?.let { put("personalLetters", Base64.encodeToString(it, Base64.NO_WRAP)) }
        }
    }

    private fun JSONObject.optBase64(key: String): ByteArray? {
        val encoded = optString(key).takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
    }

    private fun JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                add(optString(index))
            }
        }
    }

    private fun JSONObject.optStringList(key: String): List<String>? {
        val array = optJSONArray(key) ?: return null
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }
    }

    private fun JSONObject.optClipboardHistory(key: String): List<ClipboardHistoryStore.Entry>? {
        val array = optJSONArray(key) ?: return null
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ClipboardHistoryStore.Entry(
                        text = item.optString("text"),
                        timestampMillis = item.optLong("timestampMillis", 0L),
                        sourcePackageName = item.optString("sourcePackageName").takeIf { it.isNotBlank() },
                        sourceAppLabel = item.optString("sourceAppLabel").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    private fun JSONObject.optFiniteFloat(key: String, defaultValue: Float): Float {
        if (!has(key)) return defaultValue
        val value = optDouble(key, defaultValue.toDouble()).toFloat()
        return if (value.isFinite()) value else defaultValue
    }

    private fun <T> JSONObject.optById(
        key: String,
        entries: List<T>,
        defaultValue: T,
        idFor: (T) -> String,
    ): T {
        val id = optString(key, "")
        return entries.firstOrNull { idFor(it) == id } ?: defaultValue
    }

    private fun getOrCreateAgentSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEYSTORE_AGENT_API_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keySpec = KeyGenParameterSpec.Builder(
            KEYSTORE_AGENT_API_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    data class UpperRowKeyOption(
        val id: String,
        val label: String,
    )

    enum class KeyboardRow(
        val id: String,
        val label: String,
    ) {
        Upper("upper", "Shortcut row A"),
        Second("second", "Shortcut row B"),
    }

    /** The built-in providers, both reached through their OpenAI-compatible endpoint at [baseUrl]. */
    enum class AgentProviderType(
        val id: String,
        val label: String,
        val baseUrl: String,
    ) {
        Gemini("gemini", "Google Gemini", AgentDefaults.GEMINI_API_BASE_URL),
        DeepSeek("deepseek", "DeepSeek", AgentDefaults.DEEPSEEK_API_BASE_URL),
    }

    enum class AgentReasoningMode(
        val id: String,
        val label: String,
        val description: String,
        val effort: String?,
    ) {
        Off(
            id = "off",
            label = "Off",
            description = "Do not request reasoning. Safest for fast rewrites.",
            effort = null,
        ),
        Auto(
            id = "auto",
            label = "Provider default",
            description = "Let the provider decide. Useful when the selected model requires reasoning.",
            effort = null,
        ),
        Minimal(
            id = "minimal",
            label = "Minimal",
            description = "Small reasoning budget.",
            effort = "minimal",
        ),
        Low(
            id = "low",
            label = "Low",
            description = "Light reasoning with modest latency.",
            effort = "low",
        ),
        Medium(
            id = "medium",
            label = "Medium",
            description = "Balanced reasoning for harder rewrites.",
            effort = "medium",
        ),
        High(
            id = "high",
            label = "High",
            description = "More reasoning, slower and more token-heavy.",
            effort = "high",
        ),
        XHigh(
            id = "xhigh",
            label = "X-high",
            description = "Maximum reasoning for providers/models that support it.",
            effort = "xhigh",
        ),
    }

    data class AgentProviderSettings(
        val providerType: AgentProviderType,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val reasoningMode: AgentReasoningMode,
        val reasoningTextEnabled: Boolean,
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    }

    private data class EncryptedValue(
        val ciphertext: String,
        val iv: String,
    )

    private data class RestoredSecret(
        val providerType: AgentProviderType,
        val value: String,
        val locked: Boolean,
    )

    data class BackupRestoreResult(
        val schemaVersion: Int,
        val clipboardEntryCount: Int,
    )

}
