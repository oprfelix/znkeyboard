package dev.zain.znkeyboard.typing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Letter-trigram model P(c | a, b) over {boundary, a..z}.
 *
 * A bundled English prior (aggregate statistics only, see tools/typing/build_char_trigrams.py) is
 * the base measure of a Dirichlet-smoothed personal model, so the more the user types, the more
 * predictions follow their own language — Romanized Hindi/Urdu, names, jargon — instead of English.
 */
class CharLanguageModel {
    private var prior: ByteArray? = null
    private val personal = FloatArray(TRIGRAM_COUNT)
    private val contextTotals = FloatArray(CONTEXT_COUNT)

    fun setPrior(bytes: ByteArray?) {
        prior = bytes?.takeIf { it.size == TRIGRAM_COUNT }
    }

    /** Natural-log probability of letter [c] following context characters [a], [b]. */
    fun logProb(a: Char, b: Char, c: Char): Float {
        val context = index(a) * ALPHABET + index(b)
        val trigram = context * ALPHABET + index(c)
        val priorBytes = prior
        val base = if (priorBytes != null) {
            2.0.pow(-(priorBytes[trigram].toInt() and 0xFF) / PRIOR_SCALE).toFloat()
        } else {
            1f / ALPHABET
        }
        val p = (personal[trigram] + PERSONAL_PRIOR_STRENGTH * base) /
            (contextTotals[context] + PERSONAL_PRIOR_STRENGTH)
        return ln(max(p, MIN_PROBABILITY))
    }

    fun learn(a: Char, b: Char, c: Char) {
        val context = index(a) * ALPHABET + index(b)
        personal[context * ALPHABET + index(c)] += 1f
        contextTotals[context] += 1f
        if (contextTotals[context] > MAX_CONTEXT_COUNT) {
            val start = context * ALPHABET
            for (offset in 0 until ALPHABET) personal[start + offset] *= 0.5f
            contextTotals[context] *= 0.5f
        }
    }

    val learnedCount: Float
        get() = contextTotals.sum()

    fun reset() {
        personal.fill(0f)
        contextTotals.fill(0f)
    }

    fun encodePersonal(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + TRIGRAM_COUNT * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(VERSION)
        personal.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun copyPersonalInto(target: CharLanguageModel) {
        personal.copyInto(target.personal)
        contextTotals.copyInto(target.contextTotals)
    }

    fun decodePersonal(bytes: ByteArray): Boolean {
        if (bytes.size != HEADER_BYTES + TRIGRAM_COUNT * Float.SIZE_BYTES) return false
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != MAGIC || buffer.int != VERSION) return false
        val decoded = FloatArray(TRIGRAM_COUNT) { buffer.float }
        if (decoded.any { !it.isFinite() || it < 0f }) return false
        decoded.copyInto(personal)
        contextTotals.fill(0f)
        for (trigram in 0 until TRIGRAM_COUNT) contextTotals[trigram / ALPHABET] += personal[trigram]
        return true
    }

    companion object {
        const val ALPHABET = 27
        private const val CONTEXT_COUNT = ALPHABET * ALPHABET
        private const val TRIGRAM_COUNT = CONTEXT_COUNT * ALPHABET
        private const val PRIOR_SCALE = 16.0
        private const val PERSONAL_PRIOR_STRENGTH = 40f
        private const val MAX_CONTEXT_COUNT = 4_000f
        private const val MIN_PROBABILITY = 1e-4f
        private const val MAGIC = 0x5A4E4C4D // "ZNLM"
        private const val VERSION = 1
        private const val HEADER_BYTES = 8

        fun index(char: Char): Int {
            val lower = char.lowercaseChar()
            return if (lower in 'a'..'z') lower - 'a' + 1 else 0
        }

        /** Normalizes any character to the model alphabet: a lowercase letter or a space boundary. */
        fun normalize(char: Char): Char {
            val lower = char.lowercaseChar()
            return if (lower in 'a'..'z') lower else ' '
        }
    }
}

