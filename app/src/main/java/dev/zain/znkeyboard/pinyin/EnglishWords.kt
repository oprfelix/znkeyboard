package dev.zain.znkeyboard.pinyin

/**
 * Common English words offered in pinyin mode, so "email" or "invoice" needs no switch to English.
 * Letters that can't all be whole pinyin put the word first. Letters that can ("time", "change")
 * keep the best Chinese reading first, until the word has been picked by hand [PROMOTE_AT] times.
 */
class EnglishWords(words: List<String>) {
    private val displayed = HashMap<String, String>(words.size * 2)
    private val uses = LinkedHashMap<String, Int>()

    init {
        for (word in words) displayed.putIfAbsent(word.lowercase(java.util.Locale.ROOT), word)
    }

    /** [candidates] with the word [input] spells added at its place, or as they are when it spells none. */
    fun merge(
        input: String,
        candidates: List<PinyinEngine.Candidate>,
        dictionary: PinyinDictionary,
    ): List<PinyinEngine.Candidate> {
        if (input.length < MIN_LENGTH) return candidates
        val word = displayed[input] ?: return candidates
        val first = (uses[input] ?: 0) >= PROMOTE_AT || !PinyinEngine.isWholePinyin(input, dictionary)
        val at = if (first) 0 else minOf(1, candidates.size)
        return ArrayList<PinyinEngine.Candidate>(candidates.size + 1).apply {
            addAll(candidates)
            add(at, PinyinEngine.Candidate(word, input.length, emptyList(), id = CANDIDATE_ID))
        }
    }

    fun picked(word: String) {
        val key = word.lowercase(java.util.Locale.ROOT)
        uses[key] = minOf((uses[key] ?: 0) + 1, PROMOTE_AT)
        // The oldest entry goes first, so a word used long ago makes way for a new one.
        val oldest = uses.keys.iterator()
        while (uses.size > MAX_REMEMBERED) {
            oldest.next()
            oldest.remove()
        }
    }

    fun forget(word: String): Boolean = uses.remove(word.lowercase(java.util.Locale.ROOT)) != null

    fun isLearned(word: String): Boolean = word.lowercase(java.util.Locale.ROOT) in uses

    fun forgetAll() = uses.clear()

    /** One "word=uses" per line, for [restore]. */
    fun saved(): String = uses.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun restore(saved: String) {
        uses.clear()
        for (line in saved.lineSequence()) {
            val word = line.substringBefore('=')
            val count = line.substringAfter('=', "").toIntOrNull() ?: continue
            if (word in displayed && count > 0) uses[word] = minOf(count, PROMOTE_AT)
        }
    }

    companion object {
        /** [PinyinEngine.Candidate.id] of a word from here; dictionary words use 0 and up, or -1. */
        const val CANDIDATE_ID = -2
        private const val MIN_LENGTH = 4
        private const val PROMOTE_AT = 2
        private const val MAX_REMEMBERED = 500
    }
}
