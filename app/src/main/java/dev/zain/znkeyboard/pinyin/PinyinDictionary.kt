package dev.zain.znkeyboard.pinyin

import java.io.BufferedReader

/**
 * Pinyin → word index loaded from `assets/pinyin/dict.tsv` (`text<TAB>pinyin<TAB>weight`,
 * rows sorted by weight descending). Keys are fuzzy-normalized, so the index must be
 * rebuilt when the fuzzy rules change.
 */
class PinyinDictionary private constructor(
    val fuzzy: PinyinFuzzy,
    private val rows: List<Row>,
) {
    class Row(val text: String, val syllables: List<String>)

    /** One typed piece: a whole syllable that must match exactly, or the start of one. */
    data class Piece(val text: String, val isPrefix: Boolean)

    /** [rank] is the row's position by weight, used to merge buckets back into weight order. */
    private class Entry(val text: String, val syllables: Array<String>, val raw: List<String>, val rank: Int)

    /** Normalized syllables joined by spaces → entries, highest weight first. */
    private val exact = HashMap<String, MutableList<Entry>>()

    /** First letter of each raw syllable ("n h" for 你好) → entries; serves 简拼 and half-typed input. */
    private val byInitials = HashMap<String, MutableList<Entry>>()

    /** Normalized spellings of every syllable the dictionary uses. */
    private val syllables = HashSet<String>()

    /** Every prefix of a syllable, raw and normalized, so half-typed input still segments. */
    private val syllablePrefixes = HashSet<String>()

    init {
        val rawSyllables = HashSet<String>()
        // Only a few hundred distinct syllables exist; share one normalized string for each.
        val normalizedBySyllable = HashMap<String, String>()
        rows.forEachIndexed { rank, row ->
            val normalized = row.syllables.map { normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
            rawSyllables += row.syllables
            val entry = Entry(row.text, normalized.toTypedArray(), row.syllables, rank)
            exact.getOrPut(normalized.joinToString(" ")) { mutableListOf() }.add(entry)
            byInitials.getOrPut(row.syllables.joinToString(" ") { it.take(1) }) { mutableListOf() }.add(entry)
        }
        for (raw in rawSyllables) {
            val normalized = fuzzy.normalize(raw)
            syllables += normalized
            for (end in 1..raw.length) syllablePrefixes += raw.substring(0, end)
            for (end in 1..normalized.length) syllablePrefixes += normalized.substring(0, end)
        }
    }

    fun isSyllable(text: String): Boolean = fuzzy.normalize(text) in syllables

    fun isSyllablePrefix(text: String): Boolean = text in syllablePrefixes

    /**
     * Words with one syllable per piece. Exact pieces must equal their syllable; prefix pieces
     * only start it. A single letter is 简拼 for its own initial ("z" covers zh, but "n" never
     * covers l); fuzzy rules apply to longer pieces. When the last piece is a prefix, words
     * spelling it exactly come first.
     */
    fun matches(pieces: List<Piece>, limit: Int): List<String> {
        val normalized = pieces.map { fuzzy.normalize(it.text) }
        if (pieces.none { it.isPrefix }) {
            return exact[normalized.joinToString(" ")].orEmpty().asSequence().map { it.text }.take(limit).toList()
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
            .sortedBy { it.rank }
        val lastExact = candidates.asSequence().filter { it.syllables[lastIndex] == normalized[lastIndex] }
        val lastLonger = candidates.asSequence().filter { it.syllables[lastIndex] != normalized[lastIndex] }
        return (lastExact + lastLonger).map { it.text }.take(limit).toList()
    }

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

    fun withFuzzy(rules: Set<PinyinFuzzyRule>): PinyinDictionary =
        if (rules == fuzzy.rules) this else PinyinDictionary(PinyinFuzzy(rules), rows)

    companion object {
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
                    rows += Row(line.substring(0, firstTab), syllables)
                }
            }
            return PinyinDictionary(PinyinFuzzy(rules), rows)
        }
    }
}
