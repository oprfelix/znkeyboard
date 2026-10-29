package dev.zain.znkeyboard.pinyin

import java.io.BufferedReader
import kotlin.math.ln

/**
 * Pinyin → word index loaded from `assets/pinyin/dict.tsv` (`text<TAB>pinyin<TAB>weight`,
 * rows sorted by weight descending). Keys are fuzzy-normalized, so the index must be
 * rebuilt when the fuzzy rules change.
 *
 * Words rank by log weight plus a bonus for how often the user picked them ([learn]); words
 * the user built that the dictionary lacks are added with a modest base. Learning mutates the
 * index, so after construction the dictionary is main-thread only.
 */
class PinyinDictionary private constructor(
    val fuzzy: PinyinFuzzy,
    private val rows: List<Row>,
) {
    class Row(val text: String, val syllables: List<String>, val weight: Long)

    /** One typed piece: a whole syllable that must match exactly, or the start of one. */
    data class Piece(val text: String, val isPrefix: Boolean)

    /** A dictionary word: its text and raw pinyin, one syllable per character. */
    class Word(val text: String, val syllables: List<String>)

    /** A suggestion to type after a committed word: [suffix] completes it into [word]. */
    class Continuation(val suffix: String, val word: Word)

    /** [rank] is the row's position by weight and breaks score ties. */
    private class Entry(
        val text: String,
        val syllables: Array<String>,
        val raw: List<String>,
        val rank: Int,
        val base: Float,
    ) {
        var score = base
    }

    /** Normalized syllables joined by spaces → entries, best first. */
    private val exact = HashMap<String, MutableList<Entry>>()

    /** First letter of each raw syllable ("n h" for 你好) → entries; serves 简拼 and half-typed input. */
    private val byInitials = HashMap<String, MutableList<Entry>>()

    /** First character → multi-character entries, best first; serves continuations after a commit. */
    private val byFirstChar = HashMap<Char, MutableList<Entry>>()

    /** Normalized spellings of every syllable the dictionary uses. */
    private val syllables = HashSet<String>()

    /** Every prefix of a syllable, raw and normalized, so half-typed input still segments. */
    private val syllablePrefixes = HashSet<String>()

    /** Entries whose score [learn] changed, so [forgetLearned] can undo it. */
    private val learned = HashSet<Entry>()

    private val normalizedBySyllable = HashMap<String, String>()

    init {
        val rawSyllables = HashSet<String>()
        rows.forEachIndexed { rank, row ->
            rawSyllables += row.syllables
            index(newEntry(row.text, row.syllables, rank, ln(row.weight.coerceAtLeast(1L).toFloat())))
        }
        for (raw in rawSyllables) {
            val normalized = fuzzy.normalize(raw)
            syllables += normalized
            for (end in 1..raw.length) syllablePrefixes += raw.substring(0, end)
            for (end in 1..normalized.length) syllablePrefixes += normalized.substring(0, end)
        }
    }

    // Only a few hundred distinct syllables exist; share one normalized string for each.
    private fun newEntry(text: String, raw: List<String>, rank: Int, base: Float): Entry {
        val normalized = raw.map { normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
        return Entry(text, normalized.toTypedArray(), raw, rank, base)
    }

    // Rows arrive in weight order, so appending keeps every bucket sorted.
    private fun index(entry: Entry) {
        exact.getOrPut(exactKey(entry)) { mutableListOf() }.add(entry)
        byInitials.getOrPut(initialsKey(entry)) { mutableListOf() }.add(entry)
        if (entry.text.length > 1) byFirstChar.getOrPut(entry.text[0]) { mutableListOf() }.add(entry)
    }

    private fun exactKey(entry: Entry) = entry.syllables.joinToString(" ")

    private fun initialsKey(entry: Entry) = entry.raw.joinToString(" ") { it.take(1) }

    fun isSyllable(text: String): Boolean = fuzzy.normalize(text) in syllables

    fun isSyllablePrefix(text: String): Boolean = text in syllablePrefixes

    /**
     * Words with one syllable per piece. Exact pieces must equal their syllable; prefix pieces
     * only start it. A single letter is 简拼 for its own initial ("z" covers zh, but "n" never
     * covers l); fuzzy rules apply to longer pieces. When the last piece is a prefix, words
     * spelling it exactly come first.
     */
    fun matches(pieces: List<Piece>, limit: Int): List<Word> {
        val normalized = pieces.map { fuzzy.normalize(it.text) }
        if (pieces.none { it.isPrefix }) {
            return exact[normalized.joinToString(" ")].orEmpty().asSequence().map(::word).take(limit).toList()
        }
        val lastIndex = normalized.lastIndex
        fun fitsPiece(entry: Entry, i: Int): Boolean {
            val piece = pieces[i]
            return when {
                !piece.isPrefix -> entry.syllables[i] == normalized[i]
                piece.text.length == 1 -> entry.raw[i].startsWith(piece.text)
                else -> entry.syllables[i].startsWith(normalized[i]) || entry.raw[i].startsWith(piece.text)
            }
        }
        val candidates = initialsKeys(pieces)
            .flatMap { byInitials[it].orEmpty() }
            .filter { entry -> pieces.indices.all { fitsPiece(entry, it) } }
            .sortedWith(BEST_FIRST)
        val lastExact = candidates.asSequence().filter { it.syllables[lastIndex] == normalized[lastIndex] }
        val lastLonger = candidates.asSequence().filter { it.syllables[lastIndex] != normalized[lastIndex] }
        return (lastExact + lastLonger).map(::word).take(limit).toList()
    }

    /** Longer words that start with [text], best first, as the characters still to type. */
    fun continuations(text: String, limit: Int): List<Continuation> {
        if (text.isEmpty()) return emptyList()
        val bucket = byFirstChar[text[0]] ?: return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Continuation>()
        for (entry in bucket) {
            if (entry.text.length <= text.length || !entry.text.startsWith(text)) continue
            val suffix = entry.text.substring(text.length)
            if (seen.add(suffix)) result += Continuation(suffix, word(entry))
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * Applies [uses] picks of [text] spelled [raw]. A word the dictionary lacks is added, so
     * phrases the user built show up for their full pinyin and 简拼 alike.
     */
    fun learn(text: String, raw: List<String>, uses: Int) {
        if (raw.isEmpty() || raw.size != PinyinUserData.characterCount(text) || raw.any { !isSyllable(it) }) return
        val key = raw.joinToString(" ") { normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
        var entry = exact[key]?.firstOrNull { it.text == text && it.raw == raw }
        if (entry == null) {
            entry = newEntry(text, raw, Int.MAX_VALUE, NEW_WORD_BASE)
            index(entry)
        }
        entry.score = entry.base + LEARN_BONUS * ln(1f + uses)
        learned += entry
        reposition(entry)
    }

    /** Drops everything [learn] did. */
    fun forgetLearned() {
        for (entry in learned) {
            if (entry.rank == Int.MAX_VALUE) {
                exact[exactKey(entry)]?.remove(entry)
                byInitials[initialsKey(entry)]?.remove(entry)
                if (entry.text.length > 1) byFirstChar[entry.text[0]]?.remove(entry)
            } else {
                entry.score = entry.base
                reposition(entry)
            }
        }
        learned.clear()
    }

    private fun reposition(entry: Entry) {
        resort(exact[exactKey(entry)], entry)
        resort(byInitials[initialsKey(entry)], entry)
        if (entry.text.length > 1) resort(byFirstChar[entry.text[0]], entry)
    }

    /** Moves [entry] to its place in the otherwise sorted [bucket]. */
    private fun resort(bucket: MutableList<Entry>?, entry: Entry) {
        if (bucket == null || !bucket.remove(entry)) return
        var low = 0
        var high = bucket.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (BEST_FIRST.compare(bucket[mid], entry) <= 0) low = mid + 1 else high = mid
        }
        bucket.add(low, entry)
    }

    private fun word(entry: Entry) = Word(entry.text, entry.raw)

    /** Raw initials each piece may start with; only n = l can change a first letter. */
    private fun initialsKeys(pieces: List<Piece>): List<String> {
        var keys = listOf("")
        pieces.forEachIndexed { i, piece ->
            val first = piece.text.first()
            val options = if (piece.text.length > 1 && PinyinFuzzyRule.NL in fuzzy.rules && (first == 'n' || first == 'l')) {
                listOf('n', 'l')
            } else {
                listOf(first)
            }
            keys = keys.flatMap { key -> options.map { if (i == 0) "$it" else "$key $it" } }
        }
        return keys
    }

    /** A fresh index under [rules], without anything learned; the caller re-applies its words. */
    fun withFuzzy(rules: Set<PinyinFuzzyRule>): PinyinDictionary = PinyinDictionary(PinyinFuzzy(rules), rows)

    companion object {
        /** About the weight of an ordinary two-character word. */
        private val NEW_WORD_BASE = ln(20_000f)

        /** One pick multiplies a word's weight by ~16, three by ~256. */
        private const val LEARN_BONUS = 4f

        private val BEST_FIRST = compareByDescending<Entry> { it.score }.thenBy { it.rank }

        fun load(reader: BufferedReader, rules: Set<PinyinFuzzyRule>): PinyinDictionary {
            val rows = ArrayList<Row>(200_000)
            // Rows repeat the same few hundred syllables; keep one copy of each string.
            val syllableCache = HashMap<String, String>()
            reader.forEachLine { line ->
                val firstTab = line.indexOf('\t')
                val secondTab = line.indexOf('\t', firstTab + 1)
                if (firstTab > 0 && secondTab > firstTab) {
                    val syllables = line.substring(firstTab + 1, secondTab).split(' ')
                        .map { syllableCache.getOrPut(it) { it } }
                    val weight = line.substring(secondTab + 1).toLongOrNull() ?: 1L
                    rows += Row(line.substring(0, firstTab), syllables, weight)
                }
            }
            return PinyinDictionary(PinyinFuzzy(rules), rows)
        }
    }
}
