package dev.zain.znkeyboard.pinyin

/**
 * Turns typed pinyin letters into candidates. No prediction or learning: candidates come
 * straight from dictionary weights, longest match first. Full pinyin, 简拼 (initials such as
 * "nh" for 你好) and mixes of the two ("nhao") all work.
 */
object PinyinEngine {
    /** A piece cut from the input; [end] is its exclusive end offset in the raw input. */
    data class Syllable(val text: String, val end: Int)

    /** [consumed] is how many input characters picking this candidate uses up. */
    data class Candidate(val text: String, val consumed: Int)

    const val SEPARATOR = '\''
    private const val MAX_SYLLABLE_LENGTH = 6
    private const val MAX_CANDIDATES = 80
    private const val FULL_MATCH_LIMIT = 30
    private const val PARTIAL_MATCH_LIMIT = 8
    private const val SINGLE_SYLLABLE_LIMIT = 40

    /**
     * Splits [input] into pieces, longest first, backtracking when the rest would not parse.
     * A piece is a whole syllable or the start of one, so "nh" splits as n + h. Stops at the
     * first part that does not parse, so a typo leaves the remaining letters uncommitted.
     */
    fun segment(input: String, dictionary: PinyinDictionary): List<Syllable> {
        val result = mutableListOf<Syllable>()
        var start = 0
        while (start < input.length) {
            if (input[start] == SEPARATOR) {
                start++
                continue
            }
            var end = start
            while (end < input.length && input[end] != SEPARATOR) end++
            val chunk = segmentChunk(input, start, end, dictionary)
            result += chunk
            if (chunk.lastOrNull()?.end != end) break
            start = end
        }
        return result
    }

    private fun segmentChunk(input: String, start: Int, end: Int, dictionary: PinyinDictionary): List<Syllable> {
        val best = mutableListOf<Syllable>()
        // Positions from which the rest cannot be parsed; avoids exponential backtracking.
        val deadEnds = HashSet<Int>()

        fun search(position: Int, path: MutableList<Syllable>): Boolean {
            if (position == end) {
                best.clear()
                best += path
                return true
            }
            if (position in deadEnds) return false
            for (length in minOf(MAX_SYLLABLE_LENGTH, end - position) downTo 1) {
                val piece = input.substring(position, position + length)
                if (dictionary.isSyllable(piece) || dictionary.isSyllablePrefix(piece)) {
                    path += Syllable(piece, position + length)
                    if (search(position + length, path)) return true
                    path.removeAt(path.lastIndex)
                }
            }
            val reached = path.lastOrNull()?.end ?: start
            val bestReached = best.lastOrNull()?.end ?: start
            if (reached > bestReached) {
                best.clear()
                best += path
            }
            deadEnds += position
            return false
        }

        search(start, mutableListOf())
        return best
    }

    /**
     * A whole syllable of two or more letters must match exactly; single letters and
     * half-typed syllables are 简拼 and match any syllable they start. The final piece of the
     * input may always still be growing, so it matches as a prefix too.
     */
    private fun piecesFor(syllables: List<Syllable>, count: Int, dictionary: PinyinDictionary): List<PinyinDictionary.Piece> =
        (0 until count).map { i ->
            val text = syllables[i].text
            val complete = text.length >= 2 && dictionary.isSyllable(text)
            val stillTyping = i == syllables.lastIndex
            PinyinDictionary.Piece(text, isPrefix = !complete || stillTyping)
        }

    fun candidates(input: String, dictionary: PinyinDictionary): List<Candidate> {
        val syllables = segment(input, dictionary)
        if (syllables.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Candidate>()
        for (count in syllables.size downTo 1) {
            val limit = when {
                count == syllables.size -> FULL_MATCH_LIMIT
                count == 1 -> SINGLE_SYLLABLE_LIMIT
                else -> PARTIAL_MATCH_LIMIT
            }
            val words = dictionary.matches(piecesFor(syllables, count, dictionary), limit)
            // Picking a candidate also eats the separator that follows it.
            var consumed = syllables[count - 1].end
            if (consumed < input.length && input[consumed] == SEPARATOR) consumed++
            for (word in words) {
                if (seen.add(word)) result += Candidate(word, consumed)
                if (result.size >= MAX_CANDIDATES) return result
            }
        }
        return result
    }
}
