package dev.zain.znkeyboard

import android.content.Context
import org.json.JSONArray

/**
 * The user's phrases (常用语): addresses, payment details, set replies, typed from the keyboard's
 * phrases panel and edited in settings. Same rules as the iOS keyboard: newest first, no
 * duplicates, at most [MAX_PHRASES], and two to start with.
 */
object PhrasesStore {
    const val MAX_PHRASES = 100
    const val MAX_LENGTH = 4000
    val DEFAULT_PHRASES = listOf("马上到！", "我在忙，晚点回复。")

    private const val PREFS_NAME = "zn_phrases"
    private const val KEY_PHRASES = "phrases"

    fun read(context: Context): List<String> {
        val stored = prefs(context).getString(KEY_PHRASES, null) ?: return DEFAULT_PHRASES
        return runCatching {
            val array = JSONArray(stored)
            List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(DEFAULT_PHRASES)
    }

    fun write(context: Context, phrases: List<String>) {
        val array = JSONArray()
        phrases.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_PHRASES).forEach(array::put)
        prefs(context).edit().putString(KEY_PHRASES, array.toString()).apply()
    }

    /** Adds [text] at the top; false when it is empty or already there. */
    fun add(context: Context, text: String): Boolean {
        val trimmed = text.trim().take(MAX_LENGTH)
        val phrases = read(context)
        if (trimmed.isEmpty() || trimmed in phrases) return false
        write(context, listOf(trimmed) + phrases)
        return true
    }

    fun remove(context: Context, text: String) {
        write(context, read(context).filter { it != text })
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
