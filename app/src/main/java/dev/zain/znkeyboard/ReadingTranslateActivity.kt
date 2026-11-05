package dev.zain.znkeyboard

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translates text selected in another app, from the "ZnKeyboard 翻译" entry of its selection menu,
 * so it can be read in a sheet, leaving that app's text as it is. Text copied with the keyboard up
 * goes to [dev.zain.znkeyboard.ime.ReadingCardView] instead.
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
            else -> withContext(Dispatchers.IO) { translateForReading(context, text, target) }
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
            // No Surface here to set it, and the default content color is black in every theme.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                LanguagePicker(target) { language ->
                    target = language
                    KeyboardSettings.saveReadingTarget(context, language.code)
                }
                ReadingResult(state)
                ReadingButtons(state, onClose, onRetry = { attempt++ })
            }
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

private const val RESULT_MAX_HEIGHT_DP = 260
