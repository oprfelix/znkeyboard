package dev.zain.znkeyboard.typing

import kotlin.math.abs
import kotlin.math.ln

/**
 * Bayesian letter decoder: argmax over nearby letters of
 * log P(touch | key, this user's hands) + λ · log P(key | previous two characters).
 *
 * Two guarantees keep it predictable:
 *  - **Anchor.** A touch on the visual centre of a key always types that key, whatever the model says.
 *  - **Locality.** Only keys whose centres are within ~1.6 key pitches compete.
 */
class TouchDecoder(private val touchModel: () -> TouchModel, private val languageModel: CharLanguageModel) {
    private val params = FloatArray(4)

    /**
     * @param geometricLetter letter index (0..25) whose rectangular cell contains the touch.
     * @param centersXDp, centersYDp visual key centres, indexed by letter, in dp.
     * @param useLanguageModel false in password / no-learning fields.
     */
    fun decode(
        xDp: Float,
        yDp: Float,
        geometricLetter: Int,
        centersXDp: FloatArray,
        centersYDp: FloatArray,
        pitchXDp: Float,
        pitchYDp: Float,
        previous2: Char,
        previous1: Char,
        useLanguageModel: Boolean,
    ): Int {
        val anchorX = centersXDp[geometricLetter]
        val anchorY = centersYDp[geometricLetter]
        if (abs(xDp - anchorX) <= ANCHOR_X_FRACTION * pitchXDp && abs(yDp - anchorY) <= ANCHOR_Y_FRACTION * pitchYDp) {
            return geometricLetter
        }
        val model = touchModel()
        var best = geometricLetter
        var bestScore = Float.NEGATIVE_INFINITY
        for (letter in 0 until TouchModel.LETTER_COUNT) {
            val cx = centersXDp[letter]
            val cy = centersYDp[letter]
            if (abs(xDp - cx) > CANDIDATE_PITCHES * pitchXDp || abs(yDp - cy) > CANDIDATE_PITCHES * pitchYDp) continue
            model.params(letter, pitchXDp, pitchYDp, params)
            val dx = xDp - cx - params[0]
            val dy = yDp - cy - params[1]
            var score = -0.5f * (dx * dx / params[2] + dy * dy / params[3]) - 0.5f * ln(params[2] * params[3])
            if (useLanguageModel) {
                score += LANGUAGE_WEIGHT * languageModel.logProb(previous2, previous1, 'a' + letter)
            }
            if (score > bestScore) {
                bestScore = score
                best = letter
            }
        }
        return best
    }

    /**
     * How well a touch fits each letter, for decoders that weigh alternatives later (pinyin):
     * -ln N(touch; centre + μ, Σ) under this user's model, less the best letter's, so the best
     * fit is 0. Letters beyond the candidate distance get +∞. Writes all 26 into [out].
     */
    fun letterCosts(
        xDp: Float,
        yDp: Float,
        centersXDp: FloatArray,
        centersYDp: FloatArray,
        pitchXDp: Float,
        pitchYDp: Float,
        out: FloatArray,
    ) {
        val model = touchModel()
        var best = Float.POSITIVE_INFINITY
        for (letter in 0 until TouchModel.LETTER_COUNT) {
            val cx = centersXDp[letter]
            val cy = centersYDp[letter]
            if (abs(xDp - cx) > CANDIDATE_PITCHES * pitchXDp || abs(yDp - cy) > CANDIDATE_PITCHES * pitchYDp) {
                out[letter] = Float.POSITIVE_INFINITY
                continue
            }
            model.params(letter, pitchXDp, pitchYDp, params)
            val dx = xDp - cx - params[0]
            val dy = yDp - cy - params[1]
            val cost = 0.5f * (dx * dx / params[2] + dy * dy / params[3]) + 0.5f * ln(params[2] * params[3])
            out[letter] = cost
            if (cost < best) best = cost
        }
        for (letter in 0 until TouchModel.LETTER_COUNT) out[letter] -= best
    }

    companion object {
        const val ANCHOR_X_FRACTION = 0.30f
        const val ANCHOR_Y_FRACTION = 0.28f
        private const val CANDIDATE_PITCHES = 1.6f

        /** Tempered LM weight; simulation plateau is 0.6–1.0, kept low for names, code, and Hinglish. */
        const val LANGUAGE_WEIGHT = 0.7f
    }
}

