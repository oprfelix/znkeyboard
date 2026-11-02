package dev.zain.znkeyboard.typing

import kotlin.math.abs

/**
 * Turns ordinary typing into training labels without asking the user for anything.
 *
 * - A letter that survives (is not deleted within the next [PENDING_CAPACITY] keystrokes) confirms
 *   that its touch point belongs to that key, and its trigram is added to the personal language model.
 * - **Repair labelling:** typing "thw", backspacing the "w" and typing "e" within a few seconds is
 *   strong evidence the original touch was aimed at "e". When the replacement is a spatial neighbour,
 *   the deleted touch is learned as a sample of the replacement key. This is exactly the signal that
 *   teaches the model about its own mistakes.
 * - **Accepted corrections:** when pinyin reads a tap as another key (wl → wo) and the user takes
 *   that reading, [correct] relabels the tap, so it teaches the key that was meant.
 */
class TypingLearner(
    private val touchModel: () -> TouchModel,
    private val languageModel: CharLanguageModel,
    private val clock: () -> Long,
) {
    class Sample(
        val letter: Int,
        val xDp: Float,
        val yDp: Float,
        val centersXDp: FloatArray,
        val centersYDp: FloatArray,
        val pitchXDp: Float,
        val pitchYDp: Float,
    ) {
        /** The key the user meant, when a later reading corrected the tap ([correct]); -1 otherwise. */
        var correctedLetter = -1

        /** Learned already, so a correction has to be learned on its own. */
        var confirmed = false
    }

    private class Entry(val char: Char, val sample: Sample?, var timeMillis: Long)

    private val pending = ArrayDeque<Entry>()
    private val deleted = ArrayDeque<Entry>()
    private val context = StringBuilder()
    private var confirmed2 = ' '
    private var confirmed1 = ' '

    /** Monotonic count of learned events; lets the owner skip persisting when nothing changed. */
    var confirmations = 0L
        private set

    /** False in password and IME_FLAG_NO_PERSONALIZED_LEARNING fields: nothing is recorded. */
    var learningEnabled = true
        set(value) {
            if (!value) {
                pending.clear()
                deleted.clear()
            }
            field = value
        }

    fun previous2(): Char = if (context.length >= 2) context[context.length - 2] else ' '

    fun previous1(): Char = if (context.isNotEmpty()) context[context.length - 1] else ' '

    fun onCommitted(char: Char, sample: Sample?) {
        val normalized = CharLanguageModel.normalize(char)
        appendContext(normalized)
        if (!learningEnabled) return
        val now = clock()
        if (normalized == ' ') {
            deleted.clear()
        } else if (deleted.isNotEmpty()) {
            val replaced = deleted.removeLast()
            relabel(replaced, normalized, sample, now)
        }
        pending.addLast(Entry(normalized, sample, now))
        while (pending.size > PENDING_CAPACITY) confirm(pending.removeFirst())
    }

    fun onBackspace() {
        if (context.isNotEmpty()) context.setLength(context.length - 1)
        if (!learningEnabled) return
        val entry = pending.removeLastOrNull()
        if (entry == null) {
            deleted.clear()
            return
        }
        entry.timeMillis = clock()
        deleted.addLast(entry)
        while (deleted.size > PENDING_CAPACITY) deleted.removeFirst()
    }

    /** The caret moved or the field changed underneath us: accept what was typed and resync context. */
    fun onContextChanged(textBeforeCursor: CharSequence?) {
        flush()
        context.setLength(0)
        textBeforeCursor?.takeLast(CONTEXT_CAPACITY)?.forEach { context.append(CharLanguageModel.normalize(it)) }
        confirmed2 = previous2()
        confirmed1 = previous1()
    }

    /** Bulk deletion or other edits whose labels are ambiguous: drop unconfirmed samples. */
    fun discardPending() {
        pending.clear()
        deleted.clear()
    }

    fun flush() {
        while (pending.isNotEmpty()) confirm(pending.removeFirst())
        deleted.clear()
    }

    /**
     * The user accepted a reading that took [sample]'s tap as [letter]. A neighbouring key is
     * learned instead of the one typed: right away if the tap was already confirmed, else when it is.
     */
    fun correct(sample: Sample, letter: Int) {
        if (!learningEnabled || letter == sample.letter || letter !in 0 until TouchModel.LETTER_COUNT) return
        val targetX = sample.centersXDp[letter]
        val targetY = sample.centersYDp[letter]
        if (abs(sample.xDp - targetX) > NEIGHBOUR_X_PITCHES * sample.pitchXDp) return
        if (abs(sample.yDp - targetY) > NEIGHBOUR_Y_PITCHES * sample.pitchYDp) return
        if (!sample.confirmed) {
            sample.correctedLetter = letter
            return
        }
        confirmations++
        touchModel().learn(letter, sample.xDp - targetX, sample.yDp - targetY, sample.pitchXDp, sample.pitchYDp)
    }

    private fun relabel(replaced: Entry, replacement: Char, replacementSample: Sample?, now: Long) {
        val original = replaced.sample ?: return
        if (replacement == replaced.char || replacement !in 'a'..'z') return
        if (now - replaced.timeMillis > REPAIR_WINDOW_MILLIS) return
        val target = replacement - 'a'
        // Only spatial neighbours count; deleting "cat" to write "dog" is an edit, not a mistap.
        val targetX = original.centersXDp[target]
        val targetY = original.centersYDp[target]
        if (abs(original.xDp - targetX) > NEIGHBOUR_X_PITCHES * original.pitchXDp) return
        if (abs(original.yDp - targetY) > NEIGHBOUR_Y_PITCHES * original.pitchYDp) return
        // If the retyped touch landed right where the deleted one did, the user changed their mind.
        if (replacementSample != null &&
            abs(replacementSample.xDp - original.xDp) < SAME_SPOT_FRACTION * original.pitchXDp &&
            abs(replacementSample.yDp - original.yDp) < SAME_SPOT_FRACTION * original.pitchYDp &&
            replacementSample.letter == original.letter
        ) {
            return
        }
        confirmations++
        touchModel().learn(target, original.xDp - targetX, original.yDp - targetY, original.pitchXDp, original.pitchYDp)
    }

    private fun confirm(entry: Entry) {
        confirmations++
        entry.sample?.let { sample ->
            if (sample.letter == entry.char - 'a') {
                val letter = if (sample.correctedLetter >= 0) sample.correctedLetter else sample.letter
                touchModel().learn(
                    letter,
                    sample.xDp - sample.centersXDp[letter],
                    sample.yDp - sample.centersYDp[letter],
                    sample.pitchXDp,
                    sample.pitchYDp,
                )
                sample.confirmed = true
            }
        }
        if (entry.char != ' ' || confirmed1 != ' ') {
            languageModel.learn(confirmed2, confirmed1, entry.char)
        }
        confirmed2 = confirmed1
        confirmed1 = entry.char
    }

    private fun appendContext(char: Char) {
        context.append(char)
        if (context.length > CONTEXT_CAPACITY * 2) context.delete(0, context.length - CONTEXT_CAPACITY)
    }

    companion object {
        const val PENDING_CAPACITY = 10
        private const val CONTEXT_CAPACITY = 16
        const val REPAIR_WINDOW_MILLIS = 8_000L
        private const val NEIGHBOUR_X_PITCHES = 1.6f
        private const val NEIGHBOUR_Y_PITCHES = 1.25f
        private const val SAME_SPOT_FRACTION = 0.12f
    }
}

