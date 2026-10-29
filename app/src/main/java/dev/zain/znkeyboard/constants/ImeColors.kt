package dev.zain.znkeyboard.constants

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * Runtime keyboard palette. [apply] is called before the input views are (re)created, so every
 * surface reads one consistent set of colors for the current theme and light/dark mode.
 *
 * The Material You style maps Android's dynamic color tokens (android.R.color.system_*_light/_dark,
 * API 34+) onto keyboard roles the way Material 3 assigns them: letter keys on the highest-contrast
 * surface, modifier keys on quieter containers, and the action key on primary.
 */
object ImeColors {
    enum class Style(val id: String, val label: String) {
        MaterialYou("material_you", "Material You"),
        ClassicBlack("classic_black", "Classic black"),
        ;

        companion object {
            fun fromId(id: String?): Style = entries.firstOrNull { it.id == id } ?: MaterialYou
        }
    }

    var BACKGROUND = Color.BLACK
        private set
    var TEXT = Color.WHITE
        private set
    var KEY = Color.rgb(48, 48, 48)
        private set
    var KEY_DARK = Color.rgb(42, 42, 42)
        private set
    var FUNCTION = Color.rgb(56, 56, 56)
        private set
    var FUNCTION_DARK = Color.rgb(50, 50, 50)
        private set
    var ACTION = Color.rgb(48, 48, 48)
        private set
    var ON_ACTION = Color.WHITE
        private set
    var SELECTED = Color.rgb(78, 78, 78)
        private set
    var ON_SELECTED = Color.WHITE
        private set
    var BORDER = Color.rgb(62, 62, 62)
        private set
    var DIVIDER = Color.rgb(58, 58, 58)
        private set
    var AI_LOADING_SURFACE = Color.rgb(24, 24, 31)
        private set
    var PREVIEW = Color.rgb(70, 70, 70)
        private set
    var ACCENT = Color.rgb(48, 172, 226)
        private set

    // Ink blue for the round target-language key; fixed so it reads the same in every theme.
    val TRANSLATE_BADGE = Color.rgb(35, 64, 128)
    val ON_TRANSLATE_BADGE = Color.WHITE

    // Space key while it records speech: the familiar recording red, fixed across themes.
    val VOICE_RECORDING = Color.rgb(179, 38, 30)
    val ON_VOICE_RECORDING = Color.WHITE

    var MUTED_TEXT = Color.rgb(230, 230, 230)
        private set
    var DISABLED_TEXT = Color.rgb(130, 130, 130)
        private set
    var PLACEHOLDER_TEXT = Color.rgb(150, 150, 150)
        private set
    var SECTION_TEXT = Color.rgb(145, 145, 145)
        private set
    var SECONDARY_TEXT = Color.rgb(170, 170, 170)
        private set
    var TAG_TEXT = Color.rgb(170, 210, 255)
        private set
    var ERROR_TEXT = Color.rgb(255, 138, 138)
        private set
    var IS_DARK = true
        private set

    /** Applies the palette and returns a signature so callers can detect when views need rebuilding. */
    fun apply(context: Context, style: Style): String {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        when (style) {
            Style.ClassicBlack -> applyClassic(context)
            Style.MaterialYou -> if (dark) applyDynamicDark(context) else applyDynamicLight(context)
        }
        return listOf(style.id, IS_DARK, ACCENT, KEY, BACKGROUND).joinToString(":")
    }

    private fun applyClassic(context: Context) {
        IS_DARK = true
        val c = { id: Int -> context.getColor(id) }
        BACKGROUND = Color.BLACK
        TEXT = Color.WHITE
        KEY = Color.rgb(46, 46, 48)
        KEY_DARK = Color.rgb(36, 36, 38)
        FUNCTION = Color.rgb(30, 30, 32)
        FUNCTION_DARK = Color.rgb(28, 28, 30)
        ACCENT = c(android.R.color.system_accent1_200)
        ACTION = c(android.R.color.system_accent1_200)
        ON_ACTION = c(android.R.color.system_accent1_900)
        SELECTED = c(android.R.color.system_accent1_700)
        ON_SELECTED = c(android.R.color.system_accent1_50)
        BORDER = Color.rgb(58, 58, 60)
        DIVIDER = Color.rgb(50, 50, 52)
        AI_LOADING_SURFACE = Color.rgb(24, 24, 31)
        PREVIEW = Color.rgb(72, 72, 76)
        MUTED_TEXT = Color.rgb(214, 214, 218)
        DISABLED_TEXT = Color.rgb(120, 120, 124)
        PLACEHOLDER_TEXT = Color.rgb(148, 148, 152)
        SECTION_TEXT = Color.rgb(150, 150, 154)
        SECONDARY_TEXT = Color.rgb(172, 172, 176)
        TAG_TEXT = c(android.R.color.system_accent1_100)
        ERROR_TEXT = Color.rgb(255, 138, 138)
    }

    private fun applyDynamicDark(context: Context) {
        IS_DARK = true
        val c = { id: Int -> context.getColor(id) }
        BACKGROUND = c(android.R.color.system_surface_container_lowest_dark)
        TEXT = c(android.R.color.system_on_surface_dark)
        KEY = c(android.R.color.system_surface_container_highest_dark)
        KEY_DARK = c(android.R.color.system_surface_container_high_dark)
        FUNCTION = c(android.R.color.system_surface_container_dark)
        FUNCTION_DARK = c(android.R.color.system_surface_container_low_dark)
        ACCENT = c(android.R.color.system_primary_dark)
        ACTION = c(android.R.color.system_primary_dark)
        ON_ACTION = c(android.R.color.system_on_primary_dark)
        SELECTED = c(android.R.color.system_primary_container_dark)
        ON_SELECTED = c(android.R.color.system_on_primary_container_dark)
        BORDER = c(android.R.color.system_outline_variant_dark)
        DIVIDER = c(android.R.color.system_outline_variant_dark)
        AI_LOADING_SURFACE = c(android.R.color.system_surface_container_low_dark)
        PREVIEW = c(android.R.color.system_surface_bright_dark)
        MUTED_TEXT = c(android.R.color.system_on_surface_variant_dark)
        DISABLED_TEXT = withAlpha(TEXT, 0.38f)
        PLACEHOLDER_TEXT = c(android.R.color.system_on_surface_variant_dark)
        SECTION_TEXT = c(android.R.color.system_on_surface_variant_dark)
        SECONDARY_TEXT = c(android.R.color.system_on_surface_variant_dark)
        TAG_TEXT = c(android.R.color.system_tertiary_dark)
        ERROR_TEXT = c(android.R.color.system_error_dark)
    }

    private fun applyDynamicLight(context: Context) {
        IS_DARK = false
        val c = { id: Int -> context.getColor(id) }
        BACKGROUND = c(android.R.color.system_surface_container_light)
        TEXT = c(android.R.color.system_on_surface_light)
        KEY = c(android.R.color.system_surface_container_lowest_light)
        KEY_DARK = c(android.R.color.system_surface_container_low_light)
        FUNCTION = c(android.R.color.system_surface_container_highest_light)
        FUNCTION_DARK = c(android.R.color.system_surface_container_high_light)
        ACCENT = c(android.R.color.system_primary_light)
        ACTION = c(android.R.color.system_primary_light)
        ON_ACTION = c(android.R.color.system_on_primary_light)
        SELECTED = c(android.R.color.system_primary_container_light)
        ON_SELECTED = c(android.R.color.system_on_primary_container_light)
        BORDER = c(android.R.color.system_outline_variant_light)
        DIVIDER = c(android.R.color.system_outline_variant_light)
        AI_LOADING_SURFACE = c(android.R.color.system_surface_container_high_light)
        PREVIEW = c(android.R.color.system_surface_container_lowest_light)
        MUTED_TEXT = c(android.R.color.system_on_surface_variant_light)
        DISABLED_TEXT = withAlpha(TEXT, 0.38f)
        PLACEHOLDER_TEXT = c(android.R.color.system_on_surface_variant_light)
        SECTION_TEXT = c(android.R.color.system_on_surface_variant_light)
        SECONDARY_TEXT = c(android.R.color.system_on_surface_variant_light)
        TAG_TEXT = c(android.R.color.system_tertiary_light)
        ERROR_TEXT = c(android.R.color.system_error_light)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}

