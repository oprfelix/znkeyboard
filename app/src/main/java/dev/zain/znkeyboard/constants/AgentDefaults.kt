package dev.zain.znkeyboard.constants

/** [code] is shown on the keyboard; [name] goes into the translation prompt. */
data class TranslateLanguage(val code: String, val name: String)

object AgentDefaults {
    const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"
    const val OPENROUTER_API_BASE_URL = "https://openrouter.ai/api/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    const val MAX_REWRITE_SOURCE_CHARS = 12_000
    const val REQUEST_TIMEOUT_MS = 30_000
    const val MIN_REWRITE_LLM_TOKENS = 2_000
    const val MAX_REWRITE_LLM_TOKENS = 6_000
    const val CHARS_PER_OUTPUT_TOKEN_ESTIMATE = 2
    const val ERROR_PREVIEW_CHARS = 160

    const val STRUCTURED_OUTPUT_TEXT_FIELD = "text"
    const val REWRITE_BASE_PROMPT_ASSET = "prompts/rewrite_base.md"
    const val WHATSAPP_PROMPT_ASSET = "prompts/apps/whatsapp.md"
    const val TRANSLATE_PROMPT_ASSET = "prompts/translate.md"
    const val TRANSLATE_LANGUAGE_PLACEHOLDER = "{language}"
    val TRANSLATE_LANGUAGES = listOf(
        TranslateLanguage("EN", "English"),
        TranslateLanguage("ES", "Spanish"),
        TranslateLanguage("JA", "Japanese"),
        TranslateLanguage("FR", "French"),
        TranslateLanguage("DE", "German"),
    )
    val DEFAULT_TRANSLATE_LANGUAGE = TRANSLATE_LANGUAGES.first()
    const val TRANSLATE_REWRITE_LABEL = "AI 润译"
    const val TRANSLATE_REWRITE_LOADING_LABEL = "润译中…"

    /** Rewrite errors shown in Chinese while typing in pinyin mode; others stay as they are. */
    val CHINESE_ERROR_MESSAGES = mapOf(
        "Text changed. Run rewrite again." to "文字已变动，请重新点 AI 润译。",
        "Couldn't select the original text." to "无法选中原文。",
        "Couldn't apply the rewrite." to "无法替换成润译结果。",
        "Couldn't read the current field." to "无法读取输入框内容。",
        "Couldn't read the full field." to "无法读取完整的输入内容。",
        "Text is too long to rewrite." to "文字太长，无法润译。",
        "No text to rewrite." to "没有可润译的文字。",
        "Add API settings first." to "请先在设置里填好 API。",
        "Couldn't select the current text." to "无法选中当前文字。",
        "LLM request failed." to "AI 请求失败。",
        "No rewrite returned." to "AI 没有返回结果。",
        "No changes returned." to "没有需要修改的地方。",
    )
}
