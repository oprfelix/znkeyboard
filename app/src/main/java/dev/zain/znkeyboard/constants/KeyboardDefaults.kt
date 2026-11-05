package dev.zain.znkeyboard.constants

object KeyboardDefaults {
    const val MIN_HEIGHT_SCALE = 0.82f
    const val MAX_HEIGHT_SCALE = 1.24f
    const val DEFAULT_HEIGHT_SCALE = 1.0f
    const val MIN_BOTTOM_PADDING_DP = 0
    const val MAX_BOTTOM_PADDING_DP = 96
    const val DEFAULT_BOTTOM_PADDING_DP = 20

    const val MAX_UPPER_ROW_KEYS = 9
    const val MAX_SECOND_ROW_BUTTONS = MAX_UPPER_ROW_KEYS



    // Esc/Tab/Ctrl/Alt/arrows are opt-in: an empty row is hidden until keys are added to it.
    val DEFAULT_UPPER_ROW_KEY_IDS = emptyList<String>()
    val DEFAULT_SECOND_ROW_BUTTON_IDS = listOf("rewrite", "clipboard")
    /** The row before 1.9.19: a saved row equal to it was never customized. */
    val FORMER_SECOND_ROW_BUTTON_IDS = listOf("rewrite", "clipboard", "at", "ampersand", "percent", "question")
    const val SHARE_TEXT = "我在用 ZnKeyboard 输入法，和海外客户聊天时翻译、润色很方便，你也试试：https://znkeyboard.2amz.com"
    const val SHARE_TEXT_EN = "I use ZnKeyboard to translate and polish chats with overseas clients. Try it: https://znkeyboard.2amz.com"
    val DEFAULT_KEYBOARD_ROW_ORDER = listOf("second", "upper")
}
