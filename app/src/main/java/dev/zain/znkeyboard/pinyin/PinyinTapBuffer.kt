package dev.zain.znkeyboard.pinyin

/**
 * The tap behind each letter of the composing pinyin, as [SentenceDecoder.Taps]: how well it fitted
 * every key. It follows the composition from one state to the next ([follow]), so it stays lined
 * up when letters are typed, deleted, or taken by a picked candidate; any other change forgets the
 * taps, and those letters decode as if typed without one. Pure Kotlin, main-thread only, and
 * allocation-free after construction.
 */
class PinyinTapBuffer : SentenceDecoder.Taps {
    private val costs = Array(MAX_LETTERS) { FloatArray(KEYS) }
    private val present = BooleanArray(MAX_LETTERS)
    private var length = 0

    override fun costs(at: Int): FloatArray? = if (at in 0 until minOf(length, MAX_LETTERS) && present[at]) costs[at] else null

    /** Moves from composition [old] to [new]; [tap] scored the tap that typed a single letter appended, if any. */
    fun follow(old: String, new: String, tap: FloatArray?) {
        when {
            new == old -> Unit
            // A letter typed (or a separator, which has no tap).
            new.length == old.length + 1 && new.startsWith(old) -> {
                val at = old.length
                if (at < MAX_LETTERS) {
                    present[at] = tap != null
                    if (tap != null) tap.copyInto(costs[at])
                }
            }
            // Backspace, or the whole composition committed.
            new.length < old.length && old.startsWith(new) -> Unit
            // A picked candidate took the start.
            new.length < old.length && old.endsWith(new) -> {
                val shift = old.length - new.length
                for (at in 0 until minOf(new.length, MAX_LETTERS)) {
                    val from = at + shift
                    present[at] = from < MAX_LETTERS && present[from]
                    if (present[at]) costs[from].copyInto(costs[at])
                }
            }
            else -> present.fill(false)
        }
        length = new.length
    }

    companion object {
        /** SentenceDecoder reads at most this much input. */
        private const val MAX_LETTERS = 64
        private const val KEYS = 26
    }
}
