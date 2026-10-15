package dev.zain.znkeyboard

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** A user-defined shortcut key that plays back an ordered list of key combos and text. */
data class ShortcutMacro(
    val id: String,
    val label: String,
    val steps: List<MacroStep>,
) {
    val keyId: String get() = ShortcutMacros.keyIdFor(id)
    val displayLabel: String get() = label.ifBlank { ShortcutMacros.autoLabel(steps) }
}

sealed interface MacroStep {
    data class Key(
        val keyId: String,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) : MacroStep

    data class Text(val text: String) : MacroStep
}

enum class MacroKeyGroup(val label: String) {
    Letters("Letters"),
    Digits("Digits"),
    Editing("Editing & navigation"),
    Punctuation("Punctuation"),
    Function("Function keys"),
}

data class MacroKey(
    val id: String,
    val label: String,
    val keyCode: Int,
    val group: MacroKeyGroup,
)

object ShortcutMacros {
    const val KEY_ID_PREFIX = "macro:"
    const val MAX_MACROS = 40
    const val MAX_STEPS = 16
    const val MAX_LABEL_CHARS = 12
    const val MAX_TEXT_CHARS = 200
    private const val MAX_AUTO_LABEL_CHARS = 16

    val KEYS: List<MacroKey> = buildList {
        ('a'..'z').forEachIndexed { index, char ->
            add(MacroKey(char.toString(), char.toString(), KeyEvent.KEYCODE_A + index, MacroKeyGroup.Letters))
        }
        ('0'..'9').forEachIndexed { index, char ->
            add(MacroKey(char.toString(), char.toString(), KeyEvent.KEYCODE_0 + index, MacroKeyGroup.Digits))
        }
        listOf(
            Triple("esc", "Esc", KeyEvent.KEYCODE_ESCAPE),
            Triple("tab", "Tab", KeyEvent.KEYCODE_TAB),
            Triple("enter", "Enter", KeyEvent.KEYCODE_ENTER),
            Triple("space", "Space", KeyEvent.KEYCODE_SPACE),
            Triple("backspace", "Bksp", KeyEvent.KEYCODE_DEL),
            Triple("delete", "Del", KeyEvent.KEYCODE_FORWARD_DEL),
            Triple("insert", "Ins", KeyEvent.KEYCODE_INSERT),
            Triple("left", "←", KeyEvent.KEYCODE_DPAD_LEFT),
            Triple("up", "↑", KeyEvent.KEYCODE_DPAD_UP),
            Triple("down", "↓", KeyEvent.KEYCODE_DPAD_DOWN),
            Triple("right", "→", KeyEvent.KEYCODE_DPAD_RIGHT),
            Triple("home", "Home", KeyEvent.KEYCODE_MOVE_HOME),
            Triple("end", "End", KeyEvent.KEYCODE_MOVE_END),
            Triple("page_up", "PgUp", KeyEvent.KEYCODE_PAGE_UP),
            Triple("page_down", "PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
        ).forEach { (id, label, keyCode) -> add(MacroKey(id, label, keyCode, MacroKeyGroup.Editing)) }
        listOf(
            Triple("grave", "`", KeyEvent.KEYCODE_GRAVE),
            Triple("minus", "-", KeyEvent.KEYCODE_MINUS),
            Triple("equals", "=", KeyEvent.KEYCODE_EQUALS),
            Triple("left_bracket", "[", KeyEvent.KEYCODE_LEFT_BRACKET),
            Triple("right_bracket", "]", KeyEvent.KEYCODE_RIGHT_BRACKET),
            Triple("backslash", "\\", KeyEvent.KEYCODE_BACKSLASH),
            Triple("semicolon", ";", KeyEvent.KEYCODE_SEMICOLON),
            Triple("apostrophe", "'", KeyEvent.KEYCODE_APOSTROPHE),
            Triple("comma", ",", KeyEvent.KEYCODE_COMMA),
            Triple("period", ".", KeyEvent.KEYCODE_PERIOD),
            Triple("slash", "/", KeyEvent.KEYCODE_SLASH),
        ).forEach { (id, label, keyCode) -> add(MacroKey(id, label, keyCode, MacroKeyGroup.Punctuation)) }
        (1..12).forEach { number ->
            add(MacroKey("f$number", "F$number", KeyEvent.KEYCODE_F1 + number - 1, MacroKeyGroup.Function))
        }
    }

    private val keysById = KEYS.associateBy(MacroKey::id)

    // The tmux chords used to be hard-coded keys; they now ship as editable macros.
    val DEFAULT_MACROS = listOf(
        ShortcutMacro("tmux_prefix", "C-b", listOf(MacroStep.Key("b", ctrl = true))),
        ShortcutMacro("tmux_next", "C-b n", listOf(MacroStep.Key("b", ctrl = true), MacroStep.Key("n"))),
        ShortcutMacro("tmux_prev", "C-b p", listOf(MacroStep.Key("b", ctrl = true), MacroStep.Key("p"))),
    )

    val LEGACY_KEY_ID_ALIASES = DEFAULT_MACROS.associate { it.id to it.keyId }

    fun key(id: String): MacroKey? = keysById[id]

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun keyIdFor(macroId: String): String = KEY_ID_PREFIX + macroId

    fun isMacroKeyId(keyId: String): Boolean =
        keyId.startsWith(KEY_ID_PREFIX) && isValidId(keyId.removePrefix(KEY_ID_PREFIX))

    fun macroIdOf(keyId: String): String? = keyId.takeIf(::isMacroKeyId)?.removePrefix(KEY_ID_PREFIX)

    /** Human-readable step, e.g. "Ctrl + B" or "Type “:wq”". */
    fun stepLabel(step: MacroStep): String = when (step) {
        is MacroStep.Key -> buildList {
            if (step.ctrl) add("Ctrl")
            if (step.alt) add("Alt")
            if (step.shift) add("Shift")
            val label = key(step.keyId)?.label.orEmpty()
            add(if (size > 0 && label.length == 1) label.uppercase() else label)
        }.joinToString(" + ")
        is MacroStep.Text -> "Type “${step.text}”"
    }

    /** Compact tmux/emacs-style label used when the user leaves the key label blank, e.g. "C-b n". */
    fun autoLabel(steps: List<MacroStep>): String {
        val label = steps.joinToString(" ") { step ->
            when (step) {
                is MacroStep.Key -> buildString {
                    if (step.ctrl) append("C-")
                    if (step.alt) append("M-")
                    if (step.shift) append("S-")
                    append(key(step.keyId)?.label.orEmpty())
                }
                is MacroStep.Text -> step.text.trim()
            }
        }.trim()
        if (label.isEmpty()) return "Macro"
        return if (label.length <= MAX_AUTO_LABEL_CHARS) label else label.take(MAX_AUTO_LABEL_CHARS - 1) + "…"
    }

    fun normalizeLabel(label: String): String = label.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL_CHARS)

    fun normalize(macros: List<ShortcutMacro>): List<ShortcutMacro> {
        val seenIds = mutableSetOf<String>()
        return macros
            .mapNotNull { macro ->
                if (!isValidId(macro.id) || !seenIds.add(macro.id)) return@mapNotNull null
                val steps = normalizeSteps(macro.steps)
                if (steps.isEmpty()) return@mapNotNull null
                ShortcutMacro(macro.id, normalizeLabel(macro.label), steps)
            }
            .take(MAX_MACROS)
    }

    fun normalizeSteps(steps: List<MacroStep>): List<MacroStep> {
        return steps
            .mapNotNull { step ->
                when (step) {
                    is MacroStep.Key -> step.takeIf { key(it.keyId) != null }
                    is MacroStep.Text -> step.text.take(MAX_TEXT_CHARS).takeIf { it.isNotEmpty() }?.let(MacroStep::Text)
                }
            }
            .take(MAX_STEPS)
    }

    fun toJson(macros: List<ShortcutMacro>): JSONArray {
        return JSONArray().apply {
            normalize(macros).forEach { macro ->
                put(
                    JSONObject()
                        .put("id", macro.id)
                        .put("label", macro.label)
                        .put(
                            "steps",
                            JSONArray().apply {
                                macro.steps.forEach { step ->
                                    put(
                                        when (step) {
                                            is MacroStep.Key -> JSONObject()
                                                .put("type", "key")
                                                .put("key", step.keyId)
                                                .put("ctrl", step.ctrl)
                                                .put("alt", step.alt)
                                                .put("shift", step.shift)
                                            is MacroStep.Text -> JSONObject()
                                                .put("type", "text")
                                                .put("text", step.text)
                                        },
                                    )
                                }
                            },
                        ),
                )
            }
        }
    }

    fun fromJson(array: JSONArray): List<ShortcutMacro> {
        val macros = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val stepsJson = item.optJSONArray("steps") ?: continue
                val steps = buildList {
                    for (stepIndex in 0 until stepsJson.length()) {
                        val step = stepsJson.optJSONObject(stepIndex) ?: continue
                        when (step.optString("type")) {
                            "key" -> add(
                                MacroStep.Key(
                                    keyId = step.optString("key"),
                                    ctrl = step.optBoolean("ctrl", false),
                                    alt = step.optBoolean("alt", false),
                                    shift = step.optBoolean("shift", false),
                                ),
                            )
                            "text" -> add(MacroStep.Text(step.optString("text")))
                        }
                    }
                }
                add(ShortcutMacro(item.optString("id").trim(), item.optString("label"), steps))
            }
        }
        return normalize(macros)
    }

    fun parse(stored: String): List<ShortcutMacro>? {
        return try {
            fromJson(JSONArray(stored))
        } catch (_: JSONException) {
            null
        }
    }

    private fun isValidId(id: String): Boolean =
        id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
