package dev.zain.znkeyboard.pinyin

import java.nio.ByteBuffer

/**
 * Word bigram model over the dictionary's words, read from `assets/pinyin/lm.bin` (built by
 * tools/pinyin/lm/build_lm.py, which documents the layout). Word ids are distinct dictionary
 * texts in dict.tsv order, so a model only fits the dictionary it was built from: [fits] checks.
 *
 * Lookups read the buffer in place (a binary search among the pairs of one context), so the
 * buffer may be memory-mapped. Pure Kotlin and thread-safe for reads.
 */
class PinyinLanguageModel private constructor(
    private val buffer: ByteBuffer,
    /** Distinct dictionary texts the model was built for. */
    val words: Int,
    private val dictionaryRows: Int,
) {
    private val unigramAt = HEADER_BYTES
    private val backoffAt = unigramAt + (words + 2) * 2
    private val offsetsAt = backoffAt + (words + 2) * 2
    private val pairsAt = offsetsAt + (words + 3) * 4

    /** The context before the first word. */
    val start: Int get() = words

    /** The pseudo-word after the last one. */
    val end: Int get() = words + 1

    fun fits(words: Int, rows: Int): Boolean = words == this.words && rows == dictionaryRows

    /** ln P([word] | [previous]); a negative [previous] means no known context. [word] must be known. */
    fun logP(previous: Int, word: Int): Float {
        if (previous < 0) return unigram(word)
        var low = buffer.getInt(offsetsAt + previous * 4)
        var high = buffer.getInt(offsetsAt + (previous + 1) * 4) - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val pair = buffer.getInt(pairsAt + mid * 4)
            val id = pair ushr PROB_BITS
            when {
                id < word -> low = mid + 1
                id > word -> high = mid - 1
                else -> return -(pair and PROB_MASK) / PROB_SCALE
            }
        }
        return buffer.getShort(backoffAt + previous * 2) / SHORT_SCALE + unigram(word)
    }

    /** The words seen after [previous], likeliest first, with ln P(word | previous). */
    fun followers(previous: Int, limit: Int): List<Pair<Int, Float>> {
        if (previous < 0 || previous >= words || limit <= 0) return emptyList()
        val low = buffer.getInt(offsetsAt + previous * 4)
        val high = buffer.getInt(offsetsAt + (previous + 1) * 4)
        if (high <= low) return emptyList()
        val found = ArrayList<Pair<Int, Float>>(high - low)
        for (at in low until high) {
            val pair = buffer.getInt(pairsAt + at * 4)
            val id = pair ushr PROB_BITS
            if (id < words) found += id to -(pair and PROB_MASK) / PROB_SCALE
        }
        found.sortByDescending { it.second }
        return found.take(limit)
    }

    /** ln P([word]) on its own. */
    fun unigram(word: Int): Float = buffer.getShort(unigramAt + word * 2) / SHORT_SCALE

    companion object {
        private const val HEADER_BYTES = 20
        private const val VERSION = 1
        private const val PROB_BITS = 14
        private const val PROB_MASK = (1 shl PROB_BITS) - 1
        private const val PROB_SCALE = 400f
        private const val SHORT_SCALE = 1000f

        /** The model in [buffer] (big-endian, position 0), or null when it isn't one. */
        fun load(buffer: ByteBuffer): PinyinLanguageModel? {
            if (buffer.capacity() < HEADER_BYTES) return null
            val magic = ByteArray(4).also { for (i in 0 until 4) it[i] = buffer.get(i) }
            if (String(magic, Charsets.US_ASCII) != "ZNLM" || buffer.getInt(4) != VERSION) return null
            val words = buffer.getInt(8)
            val rows = buffer.getInt(12)
            val pairs = buffer.getInt(16)
            val size = HEADER_BYTES + (words + 2) * 4 + (words + 3) * 4 + pairs * 4
            if (words <= 0 || buffer.capacity() != size) return null
            return PinyinLanguageModel(buffer, words, rows)
        }
    }
}
