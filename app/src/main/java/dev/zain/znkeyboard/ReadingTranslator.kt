package dev.zain.znkeyboard

import android.content.Context
import dev.zain.znkeyboard.constants.AgentDefaults
import dev.zain.znkeyboard.constants.TranslateLanguage
import dev.zain.znkeyboard.ime.AgentChatClient
import java.util.Locale

/** A translation to read, for the reading sheet and the keyboard's card. Chinese text goes to English when the reading language is Chinese, as on Mac and Windows. */
internal sealed interface ReadingState {
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

/** Blocks on the network: call it off the main thread. */
internal fun translateForReading(context: Context, text: String, chosen: TranslateLanguage): ReadingState {
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

private val CHINESE_CODES = setOf("ZH", "TW")
private const val MIN_READING_LETTERS = 2
