package dev.zain.znkeyboard.pinyin

import java.io.BufferedReader

/**
 * Stroke (笔画) input from `assets/stroke/strokes.tsv` (`character<TAB>strokes`, most frequent
 * first, built by tools/stroke/build_strokes.py from rime-stroke). Strokes are h s p n z
 * (横 竖 撇 点 折); `*` in the input stands for any one stroke.
 */
class StrokeIndex private constructor(private val characters: Array<String>, private val codes: Array<String>) {
    /**
     * Characters whose strokes start with [input], best first: those it completes, then longer
     * ones, each by how often they are used (with the user's own picks from [dictionary]).
     * Each comes with its pinyin, so picks teach the pinyin dictionary and predictions follow.
     */
    fun candidates(input: String, dictionary: PinyinDictionary?, limit: Int = MAX_CANDIDATES): List<PinyinEngine.Candidate> {
        if (input.isEmpty()) return emptyList()
        class Hit(val order: Int, val complete: Boolean, val score: Float, val syllables: List<String>)
        val hits = ArrayList<Hit>()
        for (i in codes.indices) {
            val code = codes[i]
            if (code.length < input.length || !matches(code, input)) continue
            val info = dictionary?.characterInfo(characters[i])
            hits += Hit(i, code.length == input.length, info?.first ?: Float.NEGATIVE_INFINITY, info?.second.orEmpty())
        }
        return hits
            .sortedWith(compareByDescending<Hit> { it.complete }.thenByDescending { it.score }.thenBy { it.order })
            .take(limit)
            .map { PinyinEngine.Candidate(characters[it.order], input.length, it.syllables) }
    }

    private fun matches(code: String, input: String): Boolean {
        for (i in input.indices) {
            val c = input[i]
            if (c != WILDCARD && c != code[i]) return false
        }
        return true
    }

    companion object {
        const val WILDCARD = '*'
        private const val MAX_CANDIDATES = 60

        /** The marks shown for each stroke letter, as on the keys. */
        val MARKS = mapOf('h' to "一", 's' to "丨", 'p' to "丿", 'n' to "丶", 'z' to "乛", WILDCARD to "＊")

        fun display(input: String): String = input.map { MARKS[it] ?: it.toString() }.joinToString("")

        fun load(reader: BufferedReader): StrokeIndex {
            val characters = ArrayList<String>(9_000)
            val codes = ArrayList<String>(9_000)
            reader.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    characters += line.substring(0, tab)
                    codes += line.substring(tab + 1)
                }
            }
            return StrokeIndex(characters.toTypedArray(), codes.toTypedArray())
        }
    }
}
