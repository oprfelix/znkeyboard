package dev.zain.znkeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zain.znkeyboard.constants.AgentDefaults
import dev.zain.znkeyboard.constants.TranslateLanguage
import dev.zain.znkeyboard.ime.AgentChatClient
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translates text from another app so it can be read: faithfully, in a sheet, leaving that app's
 * text as it is. The text comes from the "ZnKeyboard 翻译" entry of a selection menu, where the app
 * offers one, or from the keyboard's offer after copying. Chinese text goes to English when the
 * reading language is Chinese, as on Mac and Windows.
 */
class ReadingTranslateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyboardSettings.migrateAgentProviders(this)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        setContent {
            ZnKeyboardTheme {
                ReadingSheet(text = text, onClose = ::finish)
            }
        }
    }
}

private sealed interface ReadingState {
    data object Working : ReadingState
    data class Done(val translation: String) : ReadingState
    data class Failed(val message: String, val needsSettings: Boolean = false) : ReadingState
}

/** More than half of the letters are Chinese characters. */
private fun isMostlyChinese(text: String): Boolean {
    val letters = text.filter(Char::isLetter)
    if (letters.isEmpty()) return false
    val chinese = letters.count { it in '一'..'鿿' || it in '㐀'..'䶿' }
    return chinese * 2 > letters.length
}

/**
 * Whether just-copied text is worth offering to translate: not a link, a code or a tracking number,
 * and not already in the language it would be read in.
 */
internal fun isWorthReading(text: String, target: TranslateLanguage): Boolean {
    if (text.length > AgentDefaults.MAX_REWRITE_SOURCE_CHARS || text.count(Char::isLetter) < MIN_READING_LETTERS) return false
    if (text.none(Char::isWhitespace) && (text.any(Char::isDigit) || "://" in text || text.startsWith("www."))) return false
    return target.code !in CHINESE_CODES || !isMostlyChinese(text)
}

private fun translationLanguage(text: String, chosen: TranslateLanguage): TranslateLanguage {
    if (chosen.code !in CHINESE_CODES || !isMostlyChinese(text)) return chosen
    return AgentDefaults.TRANSLATE_LANGUAGES.first { it.code == AgentDefaults.READING_FALLBACK_LANGUAGE_CODE }
}

private fun translate(context: Context, text: String, chosen: TranslateLanguage): ReadingState {
    val provider = KeyboardSettings.readAgentProviderSettings(context)
    if (!provider.isConfigured) {
        return ReadingState.Failed(context.getString(R.string.reading_need_key), needsSettings = true)
    }
    val prompt = context.assets.open(AgentDefaults.READ_PROMPT_ASSET).bufferedReader().use { it.readText() }
        .replace(AgentDefaults.TRANSLATE_LANGUAGE_PLACEHOLDER, translationLanguage(text, chosen).name)
    return runCatching {
        AgentChatClient.request(
            providerSettings = provider,
            systemPrompt = prompt,
            userContent = text,
            maxTokens = AgentChatClient.maxTokensFor(text),
        )
    }.fold(
        onSuccess = { ReadingState.Done(it) },
        onFailure = { error ->
            val chinese = Locale.getDefault().language == Locale.CHINESE.language
            ReadingState.Failed(AgentChatClient.friendlyError(error, chinese))
        },
    )
}

@Composable
private fun ReadingSheet(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var target by remember { mutableStateOf(KeyboardSettings.readReadingTarget(context)) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ReadingState>(ReadingState.Working) }
    val noText = stringResource(R.string.reading_no_text)
    val tooLong = stringResource(R.string.reading_too_long)

    LaunchedEffect(target, attempt) {
        state = ReadingState.Working
        state = when {
            text.isEmpty() -> ReadingState.Failed(noText)
            text.length > AgentDefaults.MAX_REWRITE_SOURCE_CHARS -> ReadingState.Failed(tooLong)
            else -> withContext(Dispatchers.IO) { translate(context, text, target) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                // Taps inside stay inside.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LanguagePicker(target) { language ->
                target = language
                KeyboardSettings.saveReadingTarget(context, language.code)
            }
            Text(
                text = stringResource(R.string.reading_original),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScrollingText(text, MaterialTheme.colorScheme.onSurfaceVariant, ORIGINAL_MAX_HEIGHT_DP)
            HorizontalDivider()
            ReadingResult(state)
            ReadingButtons(state, onClose, onRetry = { attempt++ })
        }
    }
}

@Composable
private fun LanguagePicker(target: TranslateLanguage, onChange: (TranslateLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.reading_translate_to),
            style = MaterialTheme.typography.titleSmall,
        )
        Box {
            TextButton(onClick = { expanded = true }) { Text("${translateLanguageName(target)} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (language in AgentDefaults.TRANSLATE_LANGUAGES) {
                    DropdownMenuItem(
                        text = { Text(translateLanguageName(language)) },
                        onClick = {
                            expanded = false
                            onChange(language)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollingText(text: String, color: Color, maxHeightDp: Int) {
    SelectionContainer {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeightDp.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ReadingResult(state: ReadingState) {
    when (state) {
        ReadingState.Working -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.heightIn(max = 18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.reading_translating))
        }
        is ReadingState.Done -> ScrollingText(state.translation, MaterialTheme.colorScheme.onSurface, RESULT_MAX_HEIGHT_DP)
        is ReadingState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReadingButtons(state: ReadingState, onClose: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.reading_close)) }
        when {
            state is ReadingState.Done -> Button(onClick = { copy(context, state.translation) }) {
                Text(stringResource(R.string.reading_copy))
            }
            state is ReadingState.Failed && state.needsSettings -> Button(
                onClick = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                    onClose()
                },
            ) { Text(stringResource(R.string.reading_open_settings)) }
            state is ReadingState.Failed -> Button(onClick = onRetry) { Text(stringResource(R.string.reading_retry)) }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("ZnKeyboard", text))
    Toast.makeText(context, R.string.reading_copied, Toast.LENGTH_SHORT).show()
}

private val CHINESE_CODES = setOf("ZH", "TW")
private const val MIN_READING_LETTERS = 2
private const val ORIGINAL_MAX_HEIGHT_DP = 110
private const val RESULT_MAX_HEIGHT_DP = 260
