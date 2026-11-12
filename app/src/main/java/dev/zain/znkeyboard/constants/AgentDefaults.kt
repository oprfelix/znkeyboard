package dev.zain.znkeyboard.constants

/** [code] is shown on the English keyboard and [chineseName] on the Chinese one; [name] goes into the translation prompt. */
data class TranslateLanguage(val code: String, val name: String, val chineseName: String)

object AgentDefaults {
    /** On until the user turns it off: nothing is sent without an API key and a tap. */
    const val AGENT_MODE_ENABLED = true

    /** Waits before each further try when the provider answers busy (429, 5xx); two more tries in all. */
    val BUSY_RETRY_DELAYS_MS = longArrayOf(1_000, 3_000)

    // Where the OpenAI-compatible provider's address starts before the user types their own.
    const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"
    const val DEEPSEEK_API_BASE_URL = "https://api.deepseek.com"
    const val GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
    /** The Gemini models offered, as on iOS: Flash-Lite only, since larger models cost many times more. */
    val GEMINI_MODELS = listOf(DEFAULT_GEMINI_MODEL, "gemini-3.1-flash-lite")
    const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"

    // Long enough for an email; a full-length Chinese answer still fits MAX_REWRITE_LLM_TOKENS.
    const val MAX_REWRITE_SOURCE_CHARS = 5_000
    const val REQUEST_TIMEOUT_MS = 120_000
    const val CONNECT_TIMEOUT_MS = 15_000
    const val MIN_REWRITE_LLM_TOKENS = 2_000
    // Most providers accept 8k output tokens; larger values are rejected by some (e.g. DeepSeek).
    const val MAX_REWRITE_LLM_TOKENS = 8_000
    const val CHARS_PER_OUTPUT_TOKEN_ESTIMATE = 3
    const val REWRITE_TOKEN_MARGIN = 200
    const val ERROR_PREVIEW_CHARS = 160

    const val STRUCTURED_OUTPUT_TEXT_FIELD = "text"
    const val REWRITE_BASE_PROMPT_ASSET = "prompts/rewrite_base.md"
    const val WHATSAPP_PROMPT_ASSET = "prompts/apps/whatsapp.md"
    const val TRANSLATE_PROMPT_ASSET = "prompts/translate.md"
    const val VOICE_PROMPT_ASSET = "prompts/voice.md"
    const val READ_PROMPT_ASSET = "prompts/read.md"
    const val DEFAULT_READING_LANGUAGE_CODE = "ZH"
    const val READING_FALLBACK_LANGUAGE_CODE = "EN"
    const val MAX_VOICE_CLIP_MS = 90_000L
    // Shorter holds are taken as a lingering space tap, not speech, and are never sent.
    const val MIN_VOICE_CLIP_MS = 600L
    const val MAX_VOICE_LLM_TOKENS = 4_000
    const val TRANSLATE_LANGUAGE_PLACEHOLDER = "{language}"
    // Ordered by how widely each is used internationally. Codes are two letters so the
    // keyboard chip keeps its width; TW is Traditional Chinese. Names go into the prompt.
    val TRANSLATE_LANGUAGES = listOf(
        TranslateLanguage("EN", "English", "英语"),
        TranslateLanguage("ZH", "Simplified Chinese", "简体中文"),
        TranslateLanguage("TW", "Traditional Chinese", "繁体中文"),
        TranslateLanguage("ES", "Spanish", "西班牙语"),
        TranslateLanguage("FR", "French", "法语"),
        TranslateLanguage("AR", "Arabic", "阿拉伯语"),
        TranslateLanguage("RU", "Russian", "俄语"),
        TranslateLanguage("PT", "Portuguese", "葡萄牙语"),
        TranslateLanguage("DE", "German", "德语"),
        TranslateLanguage("JA", "Japanese", "日语"),
        TranslateLanguage("KO", "Korean", "韩语"),
        TranslateLanguage("ID", "Indonesian", "印尼语"),
        TranslateLanguage("VI", "Vietnamese", "越南语"),
        TranslateLanguage("TH", "Thai", "泰语"),
    )
    val DEFAULT_TRANSLATE_LANGUAGE = TRANSLATE_LANGUAGES.first()
    // Shortcut-row labels follow the input mode: Chinese in 中, English in EN.
    const val TRANSLATE_REWRITE_LABEL = "翻译"
    /** The key's label while text is selected: only that is translated. */
    const val TRANSLATE_SELECTION_LABEL = "翻译选中"
    const val TRANSLATE_REWRITE_LABEL_EN = "Translate"
    const val TRANSLATE_REWRITE_LOADING_LABEL = "处理中…"
    const val TRANSLATE_REWRITE_LOADING_LABEL_EN = "Working…"
    const val POLISH_ONLY_LABEL = "润色"
    const val POLISH_ONLY_LABEL_EN = "Polish"

    /** Rewrite errors shown in Chinese while typing in pinyin mode; others stay as they are. */
    val CHINESE_ERROR_MESSAGES = mapOf(
        "Text changed. Run rewrite again." to "文字已变动，请重新点润色或 AI 翻译。",
        "Couldn't select the original text." to "无法选中原文。",
        "Couldn't apply the rewrite." to "无法替换成 AI 结果。",
        "Couldn't read the current field." to "无法读取输入框内容。",
        "Couldn't read the full field." to "无法读取完整的输入内容。",
        "Text is too long to rewrite." to "文字太长，无法润色或翻译。",
        "No text to rewrite." to "没有可润色或翻译的文字。",
        "Add API settings first." to "请先在 设置 → AI 与语音 里填写 API Key。",
        "Couldn't select the current text." to "无法选中当前文字。",
        "LLM request failed." to "AI 请求失败。",
        "No rewrite returned." to "AI 没有返回结果。",
        "No changes returned." to "没有需要修改的地方。",
    )
}
