package dev.zain.znkeyboard.typing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Personal, per-key touch distribution for the 26 letter keys.
 *
 * Each key keeps exponentially-forgotten sufficient statistics (n, Σdx, Σdy, Σdx², Σdy²) of where
 * this user's finger actually lands relative to the key's visual centre, in dp. Estimates are
 * shrunk hierarchically: key → its keyboard region (row × left/right thumb side) → a neutral prior.
 * Rare keys such as q or z therefore borrow the offset the user shows on neighbouring keys instead
 * of starting from nothing, while frequent keys quickly get their own estimate.
 */
class TouchModel {
    private val stats = FloatArray(LETTER_COUNT * STAT_COUNT)

    val sampleCount: Float
        get() {
            var total = 0f
            for (letter in 0 until LETTER_COUNT) total += stats[letter * STAT_COUNT]
            return total
        }

    fun learn(letter: Int, dxDp: Float, dyDp: Float, pitchXDp: Float, pitchYDp: Float) {
        if (letter !in 0 until LETTER_COUNT) return
        // A touch more than a full key away was not an attempt at this key; ignore it.
        if (abs(dxDp) > pitchXDp || abs(dyDp) > pitchYDp) return
        val base = letter * STAT_COUNT
        for (index in 0 until STAT_COUNT) stats[base + index] *= DECAY
        stats[base] += 1f
        stats[base + 1] += dxDp
        stats[base + 2] += dyDp
        stats[base + 3] += dxDp * dxDp
        stats[base + 4] += dyDp * dyDp
    }

    /**
     * Writes the posterior (meanX, meanY, varianceX, varianceY) for [letter] into [out] in dp / dp².
     * Means are clamped to stay within 45% of a key pitch so learning can never move a key's
     * effective centre onto a neighbour.
     */
    fun params(letter: Int, pitchXDp: Float, pitchYDp: Float, out: FloatArray) {
        val region = REGION_OF_LETTER[letter]
        var rn = 0f
        var rsx = 0f
        var rsy = 0f
        var rsxx = 0f
        var rsyy = 0f
        for (other in 0 until LETTER_COUNT) {
            if (REGION_OF_LETTER[other] != region) continue
            val base = other * STAT_COUNT
            rn += stats[base]
            rsx += stats[base + 1]
            rsy += stats[base + 2]
            rsxx += stats[base + 3]
            rsyy += stats[base + 4]
        }
        val priorVariance = PRIOR_SIGMA_DP * PRIOR_SIGMA_DP
        val regionWeight = REGION_PRIOR_STRENGTH + rn
        val regionMeanX = rsx / regionWeight
        val regionMeanY = rsy / regionWeight
        val regionVarX = (REGION_PRIOR_STRENGTH * priorVariance + rsxx) / regionWeight -
            regionMeanX * regionMeanX * (rn / regionWeight)
        val regionVarY = (REGION_PRIOR_STRENGTH * priorVariance + rsyy) / regionWeight -
            regionMeanY * regionMeanY * (rn / regionWeight)

        val base = letter * STAT_COUNT
        val n = stats[base]
        val keyWeight = KEY_PRIOR_STRENGTH + n
        val meanX = (KEY_PRIOR_STRENGTH * regionMeanX + stats[base + 1]) / keyWeight
        val meanY = (KEY_PRIOR_STRENGTH * regionMeanY + stats[base + 2]) / keyWeight
        val varX = (KEY_PRIOR_STRENGTH * (regionVarX + regionMeanX * regionMeanX) + stats[base + 3]) / keyWeight -
            meanX * meanX
        val varY = (KEY_PRIOR_STRENGTH * (regionVarY + regionMeanY * regionMeanY) + stats[base + 4]) / keyWeight -
            meanY * meanY

        out[0] = meanX.coerceIn(-MAX_MEAN_PITCH_FRACTION * pitchXDp, MAX_MEAN_PITCH_FRACTION * pitchXDp)
        out[1] = meanY.coerceIn(-MAX_MEAN_PITCH_FRACTION * pitchYDp, MAX_MEAN_PITCH_FRACTION * pitchYDp)
        out[2] = varX.coerceIn(MIN_SIGMA_DP * MIN_SIGMA_DP, MAX_SIGMA_DP * MAX_SIGMA_DP)
        out[3] = varY.coerceIn(MIN_SIGMA_DP * MIN_SIGMA_DP, MAX_SIGMA_DP * MAX_SIGMA_DP)
    }

    fun samplesFor(letter: Int): Float = stats[letter * STAT_COUNT]

    fun reset() {
        stats.fill(0f)
    }

    fun copyFrom(other: TouchModel) {
        other.stats.copyInto(stats)
    }

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + stats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(VERSION)
        stats.forEach(buffer::putFloat)
        return buffer.array()
    }

    companion object {
        const val LETTER_COUNT = 26
        private const val STAT_COUNT = 5
        private const val MAGIC = 0x5A4E544D // "ZNTM"
        private const val VERSION = 1
        private const val HEADER_BYTES = 8

        /** Neutral tap scatter before anything is learned (~1.6 mm). */
        const val PRIOR_SIGMA_DP = 10f
        const val MIN_SIGMA_DP = 5f
        const val MAX_SIGMA_DP = 20f
        private const val KEY_PRIOR_STRENGTH = 15f
        private const val REGION_PRIOR_STRENGTH = 30f
        private const val MAX_MEAN_PITCH_FRACTION = 0.45f

        /** Per-sample forgetting on the key being learned; roughly the last ~300 taps of a key matter. */
        private const val DECAY = 0.997f

        /** QWERTY row × thumb side: 0/1 top-left/right, 2/3 home-left/right, 4/5 bottom-left/right. */
        private val REGION_OF_LETTER = IntArray(LETTER_COUNT).also { regions ->
            fun assign(letters: String, region: Int) = letters.forEach { regions[it - 'a'] = region }
            assign("qwert", 0)
            assign("yuiop", 1)
            assign("asdfg", 2)
            assign("hjkl", 3)
            assign("zxcv", 4)
            assign("bnm", 5)
        }

        fun decode(bytes: ByteArray): TouchModel? {
            if (bytes.size != HEADER_BYTES + LETTER_COUNT * STAT_COUNT * Float.SIZE_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != MAGIC || buffer.int != VERSION) return null
            val model = TouchModel()
            for (index in model.stats.indices) {
                val value = buffer.float
                if (!value.isFinite()) return null
                model.stats[index] = value
            }
            return model
        }
    }
}

