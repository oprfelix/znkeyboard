package dev.zain.znkeyboard.pinyin

/**
 * The tap behind each letter of the composing pinyin, as [SentenceDecoder.Taps]: how well it fitted
 * every key, and the keyboard's own record of it ([token], for teaching the touch model what was
 * meant). It follows the composition from one state to the next ([follow]), so it stays lined up
 * when letters are typed, deleted, or taken by a picked candidate; any other change forgets the
 * taps, and those letters decode as if typed without one. Pure Kotlin, main-thread only, and
 * allocation-free after construction.
 */
class PinyinTapBuffer : SentenceDecoder.Taps {
    private val costs = Array(MAX_LETTERS) { FloatArray(KEYS) }
    private val present = BooleanArray(MAX_LETTERS)
    private val tokens = arrayOfNulls<Any>(MAX_LETTERS)
    private var length = 0

    override fun costs(at: Int): FloatArray? = if (at in 0 until minOf(length, MAX_LETTERS) && present[at]) costs[at] else null

    /** What the keyboard passed along with the tap at [at], if it is still lined up. */
    fun token(at: Int): Any? = if (at in 0 until minOf(length, MAX_LETTERS)) tokens[at] else null

    /**
     * Moves from composition [old] to [new]; [tap] scored the tap that typed a single letter
     * appended, if any, and [token] goes with it.
     */
    fun follow(old: String, new: String, tap: FloatArray?, token: Any? = null) {
        when {
            new == old -> Unit
            // A letter typed (or a separator, which has no tap).
            new.length == old.length + 1 && new.startsWith(old) -> {
                val at = old.length
                if (at < MAX_LETTERS) {
                    present[at] = tap != null
                    if (tap != null) tap.copyInto(costs[at])
                    tokens[at] = token
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
                    tokens[at] = if (from < MAX_LETTERS) tokens[from] else null
                }
                for (at in new.length until minOf(old.length, MAX_LETTERS)) tokens[at] = null
            }
            else -> {
                present.fill(false)
                tokens.fill(null)
            }
        }
        length = new.length
    }

    companion object {
        /**
         * Letters of [typed] that [spelling] (a reading's, spaces between syllables) reads as
         * another letter: (index in [typed], letter meant). Letters added, dropped or swapped
         * are not substitutions and are left out; so is anything when they don't line up.
         */
        fun substitutions(typed: String, spelling: String): List<Pair<Int, Char>> {
            val at = typed.indices.filter { typed[it] != PinyinEngine.SEPARATOR }
            val a = String(CharArray(at.size) { typed[at[it]] })
            val b = spelling.replace(" ", "")
            // Edit distance with swaps, then walk back taking substitutions.
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
            if (d[a.length][b.length] > MAX_EDITS) return emptyList()
            val result = ArrayList<Pair<Int, Char>>()
            var i = a.length
            var j = b.length
            while (i > 0 && j > 0) {
                when {
                    i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] && d[i][j] == d[i - 2][j - 2] + 1 -> { i -= 2; j -= 2 }
                    d[i][j] == d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1 -> {
                        if (a[i - 1] != b[j - 1]) result += at[i - 1] to b[j - 1]
                        i--
                        j--
                    }
                    d[i][j] == d[i - 1][j] + 1 -> i--
                    else -> j--
                }
            }
            return result.reversed()
        }

        /** More edits than this and the reading is not a correction of what was typed. */
        private const val MAX_EDITS = 4

        /** SentenceDecoder reads at most this much input. */
        private const val MAX_LETTERS = 64
        private const val KEYS = 26
    }
}
