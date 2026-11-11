package dev.zain.znkeyboard.pinyin

import java.nio.ByteBuffer

/**
 * Word bigram model over the dictionary's words, read from `assets/pinyin/lm.bin` (built by
 * tools/pinyin/lm/build_lm.py, which documents the layout). Word ids are distinct dictionary
 * texts in dict.tsv order, so a model only fits the dictionary it was built from: [fits] checks.
 *
 * Each context's pairs are blocks of Rice-coded word gaps with a byte per probability. A lookup
 * binary-searches the context's block heads and decodes at most one block, reading the buffer in
 * place, so the buffer may be memory-mapped. Pure Kotlin and thread-safe for reads.
 */
class PinyinLanguageModel private constructor(
    private val buffer: ByteBuffer,
    /** Distinct dictionary texts the model was built for. */
    val words: Int,
    private val dictionaryRows: Int,
    pairs: Int,
    blocks: Int,
    private val blockSize: Int,
) {
    private val unigramAt = HEADER_BYTES
    private val backoffAt = unigramAt + (words + 2) * 2
    private val codebookAt = backoffAt + (words + 2) * 2
    private val pairOffsetsAt = codebookAt + CODES * 2
    private val blockOffsetsAt = pairOffsetsAt + (words + 3) * 4
    private val headsAt = blockOffsetsAt + (words + 3) * 4
    private val bitOffsetsAt = headsAt + blocks * 4
    private val codesAt = bitOffsetsAt + blocks * 4
    private val streamAt = codesAt + pairs
    private val codebook = FloatArray(CODES) { -buffer.getShort(codebookAt + it * 2) / PROB_SCALE }

    /** The context before the first word. */
    val start: Int get() = words

    /** The pseudo-word after the last one. */
    val end: Int get() = words + 1

    fun fits(words: Int, rows: Int): Boolean = words == this.words && rows == dictionaryRows

    /** ln P([word] | [previous]); a negative [previous] means no known context. [word] must be known. */
    fun logP(previous: Int, word: Int): Float {
        if (previous < 0) return unigram(word)
        val firstBlock = buffer.getInt(blockOffsetsAt + previous * 4)
        var low = firstBlock
        var high = buffer.getInt(blockOffsetsAt + (previous + 1) * 4) - 1
        // The last block that starts at or before [word].
        var block = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (headId(mid) <= word) {
                block = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (block >= 0) {
            val pair = buffer.getInt(pairOffsetsAt + previous * 4) + (block - firstBlock) * blockSize
            val count = minOf(blockSize, buffer.getInt(pairOffsetsAt + (previous + 1) * 4) - pair)
            val head = buffer.getInt(headsAt + block * 4)
            val k = head and RICE_MASK
            var id = head ushr RICE_BITS
            var bit = buffer.getInt(bitOffsetsAt + block * 4).toLong()
            var at = 0
            while (true) {
                if (id == word) return probability(pair + at)
                if (id > word || ++at >= count) break
                val gap = gap(bit, k)
                id += (gap ushr 32).toInt()
                bit += gap and 0xFFFFFFFFL
            }
        }
        return buffer.getShort(backoffAt + previous * 2) / SHORT_SCALE + unigram(word)
    }

    /** The words seen after [previous], likeliest first, with ln P(word | previous). */
    fun followers(previous: Int, limit: Int): List<Pair<Int, Float>> {
        if (previous < 0 || previous >= words || limit <= 0) return emptyList()
        val firstPair = buffer.getInt(pairOffsetsAt + previous * 4)
        val endPair = buffer.getInt(pairOffsetsAt + (previous + 1) * 4)
        if (endPair <= firstPair) return emptyList()
        val firstBlock = buffer.getInt(blockOffsetsAt + previous * 4)
        val endBlock = buffer.getInt(blockOffsetsAt + (previous + 1) * 4)
        val found = ArrayList<Pair<Int, Float>>(endPair - firstPair)
        for (block in firstBlock until endBlock) {
            val pair = firstPair + (block - firstBlock) * blockSize
            val count = minOf(blockSize, endPair - pair)
            val head = buffer.getInt(headsAt + block * 4)
            val k = head and RICE_MASK
            var id = head ushr RICE_BITS
            var bit = buffer.getInt(bitOffsetsAt + block * 4).toLong()
            for (at in 0 until count) {
                if (at > 0) {
                    val gap = gap(bit, k)
                    id += (gap ushr 32).toInt()
                    bit += gap and 0xFFFFFFFFL
                }
                if (id < words) found += id to probability(pair + at)
            }
        }
        found.sortByDescending { it.second }
        return found.take(limit)
    }

    /** ln P([word]) on its own. */
    fun unigram(word: Int): Float = buffer.getShort(unigramAt + word * 2) / SHORT_SCALE

    private fun headId(block: Int): Int = buffer.getInt(headsAt + block * 4) ushr RICE_BITS

    private fun probability(pair: Int): Float = codebook[buffer.get(codesAt + pair).toInt() and 0xFF]

    /**
     * The Rice-coded gap at [bit] with parameter [k] (the quotient in unary as ones and a zero,
     * then k bits): the word gap in the high half, the bits it took in the low half.
     */
    private fun gap(bit: Long, k: Int): Long {
        var at = bit
        var quotient = 0
        while (true) {
            // The next 57 or more bits at the top of a long; the bits shifted in below are past them.
            val ones = java.lang.Long.numberOfLeadingZeros(window(at).inv())
            if (ones < WINDOW_BITS) {
                quotient += ones
                at += ones + 1
                break
            }
            quotient += WINDOW_BITS
            at += WINDOW_BITS
        }
        val remainder = if (k == 0) 0 else (window(at) ushr (64 - k)).toInt()
        at += k
        return ((((quotient shl k) or remainder) + 1).toLong() shl 32) or (at - bit)
    }

    /** The stream's bits from [bit] on, the first at the top. */
    private fun window(bit: Long): Long = buffer.getLong(streamAt + (bit ushr 3).toInt()) shl (bit and 7).toInt()

    companion object {
        private const val HEADER_BYTES = 32
        private const val VERSION = 2
        private const val CODES = 256
        private const val RICE_BITS = 5
        private const val RICE_MASK = (1 shl RICE_BITS) - 1
        private const val PROB_SCALE = 400f
        private const val SHORT_SCALE = 1000f
        /** Zero bytes after the stream, so a long read near its end stays in the buffer. */
        private const val STREAM_PADDING = 8
        /** Bits of a [window] that are surely the stream's: 64 less up to 7 shifted in. */
        private const val WINDOW_BITS = 57

        /** The model in [buffer] (big-endian, position 0), or null when it isn't one. */
        fun load(buffer: ByteBuffer): PinyinLanguageModel? {
            if (buffer.capacity() < HEADER_BYTES) return null
            val magic = ByteArray(4).also { for (i in 0 until 4) it[i] = buffer.get(i) }
            if (String(magic, Charsets.US_ASCII) != "ZNLM" || buffer.getInt(4) != VERSION) return null
            val words = buffer.getInt(8)
            val rows = buffer.getInt(12)
            val pairs = buffer.getInt(16)
            val blocks = buffer.getInt(20)
            val blockSize = buffer.getInt(24)
            val streamBytes = buffer.getInt(28)
            if (words <= 0 || pairs < 0 || blocks < 0 || blockSize <= 0 || streamBytes < 0) return null
            val size = HEADER_BYTES.toLong() + (words + 2) * 4L + CODES * 2L + (words + 3) * 8L + blocks * 8L +
                pairs + streamBytes + STREAM_PADDING
            if (buffer.capacity().toLong() != size) return null
            return PinyinLanguageModel(buffer, words, rows, pairs, blocks, blockSize)
        }
    }
}
