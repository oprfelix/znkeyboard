package dev.zain.znkeyboard.pinyin

/**
 * What the user has typed in pinyin: words they picked (including phrases built from several
 * picks) and which word tends to follow which. Pure Kotlin and main-thread only; the IME owns
 * persistence. Counts are capped so the table stays small, dropping the least-used entries.
 */
class PinyinUserData {
    /** A picked word. [syllables] are raw pinyin, one per character. */
    class Word(val text: String, val syllables: List<String>, var uses: Int, var lastUsed: Long)

    private class Follower(val next: String, var uses: Int, var lastUsed: Long)

    private val words = LinkedHashMap<String, Word>()
    private val following = HashMap<String, MutableList<Follower>>()
    private var pairCount = 0

    /** Bumped on every change, so callers can tell what is new since a snapshot. */
    var clock = 0L
        private set

    val isEmpty: Boolean get() = words.isEmpty() && pairCount == 0

    fun words(): Collection<Word> = words.values

    /** Records a pick of [text]; returns the updated word. */
    fun learnWord(text: String, syllables: List<String>): Word {
        clock++
        val word = words.getOrPut(key(text, syllables)) { Word(text, syllables, 0, 0) }
        word.uses = (word.uses + 1).coerceAtMost(MAX_USES)
        word.lastUsed = clock
        if (words.size > MAX_WORDS) pruneWords()
        return word
    }

    /** Records that [next] was typed right after [previous]. */
    fun learnPair(previous: String, next: String) {
        if (previous.isEmpty() || next.isEmpty()) return
        clock++
        val list = following.getOrPut(previous) { ArrayList(2) }
        val pair = list.firstOrNull { it.next == next } ?: Follower(next, 0, 0).also {
            list += it
            pairCount++
        }
        pair.uses = (pair.uses + 1).coerceAtMost(MAX_USES)
        pair.lastUsed = clock
        if (pairCount > MAX_PAIRS) prunePairs()
    }

    /** Words typed after [previous] before, most used first. */
    fun nextWords(previous: String, limit: Int): List<String> {
        val list = following[previous] ?: return emptyList()
        return list.sortedWith(compareByDescending<Follower> { it.uses }.thenByDescending { it.lastUsed })
            .take(limit)
            .map { it.next }
    }

    /** Words learned or changed after [since], by clock. */
    fun wordsChangedSince(since: Long): List<Word> = words.values.filter { it.lastUsed > since }

    fun encode(): ByteArray {
        val out = StringBuilder()
        out.append(HEADER).append('\n')
        for (word in words.values) {
            out.append('w').append('\t').append(word.text).append('\t').append(word.syllables.joinToString(" "))
                .append('\t').append(word.uses).append('\t').append(word.lastUsed).append('\n')
        }
        for ((previous, list) in following) {
            for (pair in list) {
                out.append('p').append('\t').append(previous).append('\t').append(pair.next)
                    .append('\t').append(pair.uses).append('\t').append(pair.lastUsed).append('\n')
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Drops the least-used quarter when a table overflows. */
    private fun pruneWords() {
        val keep = words.values.sortedWith(compareByDescending<Word> { it.uses }.thenByDescending { it.lastUsed })
            .take(MAX_WORDS * 3 / 4)
            .toHashSet()
        words.values.retainAll(keep)
    }

    private fun prunePairs() {
        val all = following.flatMap { (previous, list) -> list.map { previous to it } }
            .sortedWith(compareByDescending<Pair<String, Follower>> { it.second.uses }.thenByDescending { it.second.lastUsed })
            .take(MAX_PAIRS * 3 / 4)
        following.clear()
        all.forEach { (previous, pair) -> following.getOrPut(previous) { ArrayList(2) } += pair }
        pairCount = all.size
    }

    companion object {
        private const val HEADER = "zn-pinyin-user 1"
        const val MAX_WORDS = 6_000
        const val MAX_PAIRS = 6_000
        private const val MAX_USES = 10_000

        private fun key(text: String, syllables: List<String>) = text + '\t' + syllables.joinToString(" ")

        /** Characters, not UTF-16 units: rare hanzi sit outside the BMP. */
        fun characterCount(text: String): Int = text.codePointCount(0, text.length)

        /** Null when [bytes] is not a file written by [encode]. */
        fun decode(bytes: ByteArray): PinyinUserData? {
            val lines = bytes.toString(Charsets.UTF_8).lineSequence().iterator()
            if (!lines.hasNext() || lines.next() != HEADER) return null
            val data = PinyinUserData()
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size != 5) continue
                val uses = parts[3].toIntOrNull()?.coerceIn(1, MAX_USES) ?: continue
                val lastUsed = parts[4].toLongOrNull() ?: continue
                when (parts[0]) {
                    "w" -> {
                        val syllables = parts[2].split(' ')
                        if (parts[1].isEmpty() || syllables.size != characterCount(parts[1])) continue
                        data.words[key(parts[1], syllables)] = Word(parts[1], syllables, uses, lastUsed)
                    }
                    "p" -> {
                        if (parts[1].isEmpty() || parts[2].isEmpty()) continue
                        data.following.getOrPut(parts[1]) { ArrayList(2) } += Follower(parts[2], uses, lastUsed)
                        data.pairCount++
                    }
                    else -> continue
                }
                data.clock = maxOf(data.clock, lastUsed)
            }
            return data
        }
    }
}
