package dev.zain.znkeyboard.pinyin

/**
 * Whole-sentence input: finds the best way to read all of the typed pinyin as a row of
 * dictionary words, so "womingtianxiangqupashan" becomes 我明天想去爬山 in one candidate.
 *
 * Every split is tried: whole syllables, 简拼 initials ("ymy" for 有没有), mixes of the two, and
 * syllables with one typing slip (min → ming, xang → xiang, xiamg → xiang, xinag → xiang). Each
 * word costs a fixed amount less its score (log weight plus what the user picked), so fewer,
 * commoner words win; slips and initials cost extra, so a correct spelling is never "fixed".
 * Pure Kotlin and main-thread only, like [PinyinDictionary].
 */
object SentenceDecoder {
    /**
     * The best reading of [input], or null when it can't be read whole. [parts] are the words,
     * for learning; [spelling] is the pinyin as read, slips corrected ("wo ming tian y m y").
     */
    class Sentence(val text: String, val parts: List<PinyinEngine.Candidate>, val spelling: String, val cost: Float)

    /** One way to read input[start, end): a whole syllable (possibly corrected) or the start of one. */
    private class Piece(val end: Int, val text: String, val isPrefix: Boolean, val cost: Float)

    private class Step(val cost: Float, val from: Int, val word: PinyinDictionary.Word?, val pieces: List<Piece>)

    fun decode(input: String, dictionary: PinyinDictionary): Sentence? {
        val n = input.length
        if (n == 0 || n > MAX_INPUT) return null
        val piecesAt = arrayOfNulls<List<Piece>>(n)
        fun pieces(at: Int): List<Piece> = piecesAt[at] ?: readPieces(input, at, dictionary).also { piecesAt[at] = it }

        val best = arrayOfNulls<Step>(n + 1)
        best[0] = Step(0f, -1, null, emptyList())
        for (start in 0 until n) {
            val here = best[start] ?: continue
            if (input[start] == PinyinEngine.SEPARATOR) {
                relax(best, start + 1, Step(here.cost, start, null, emptyList()))
                continue
            }
            // Runs of pieces from here, as long as some word's initials still start that way. A
            // separator only splits syllables, so a word may run across it (xi'an → 西安).
            fun extend(at: Int, run: MutableList<Piece>, initials: String) {
                if (run.isNotEmpty()) {
                    val match = bestMatch(run, dictionary)
                    if (match != null) {
                        val cost = here.cost + WORD_COST - match.second + run.sumOf { it.cost.toDouble() }.toFloat()
                        relax(best, at, Step(cost, start, match.first, run.toList()))
                    }
                }
                if (run.size >= MAX_WORD_SYLLABLES || at >= n) return
                if (input[at] == PinyinEngine.SEPARATOR) {
                    if (run.isNotEmpty()) extend(at + 1, run, initials)
                    return
                }
                for (piece in pieces(at)) {
                    val next = if (initials.isEmpty()) "${piece.text[0]}" else "$initials ${piece.text[0]}"
                    if (!dictionary.hasInitialsPrefix(next)) continue
                    run += piece
                    extend(piece.end, run, next)
                    run.removeAt(run.lastIndex)
                }
            }
            extend(start, ArrayList(), "")
        }

        val last = best[n] ?: return null
        val words = ArrayList<PinyinDictionary.Word>()
        val spelling = ArrayList<String>()
        var step: Step = last
        while (step.from >= 0) {
            step.word?.let { word ->
                words += word
                spelling += step.pieces.joinToString(" ") { it.text }
            }
            step = best[step.from] ?: break
        }
        words.reverse()
        spelling.reverse()
        if (words.isEmpty()) return null
        val parts = words.map { PinyinEngine.Candidate(it.text, n, it.syllables) }
        return Sentence(words.joinToString("") { it.text }, parts, spelling.joinToString(" "), last.cost)
    }

    /**
     * 9-key digits (no separators or locked syllables) read as a sentence: each word is a run of
     * digits that is its whole code or its 简拼.
     */
    fun decodeNineKey(digits: String, dictionary: PinyinDictionary): Sentence? {
        val n = digits.length
        if (n < 2 || n > MAX_INPUT) return null
        class Edge(val cost: Float, val from: Int, val word: PinyinDictionary.Word?)
        val best = arrayOfNulls<Edge>(n + 1)
        best[0] = Edge(0f, -1, null)
        for (start in 0 until n) {
            val here = best[start] ?: continue
            for (end in start + 1..minOf(n, start + MAX_NINE_KEY_WORD_DIGITS)) {
                for (match in dictionary.nineKeyWords(digits.substring(start, end))) {
                    val extra = if (match.byInitials) INITIAL_COST * match.word.syllables.size else 0f
                    val cost = here.cost + WORD_COST - match.score + extra
                    val current = best[end]
                    if (current == null || cost < current.cost) best[end] = Edge(cost, start, match.word)
                }
            }
        }
        val last = best[n] ?: return null
        val words = ArrayList<PinyinDictionary.Word>()
        var edge: Edge = last
        while (edge.from >= 0) {
            edge.word?.let(words::add)
            edge = best[edge.from] ?: break
        }
        words.reverse()
        if (words.size < 2) return null
        val parts = words.map { PinyinEngine.Candidate(it.text, n, it.syllables) }
        return Sentence(words.joinToString("") { it.text }, parts, words.flatMap { it.syllables }.joinToString(" "), last.cost)
    }

    /** Lookups repeat from keystroke to keystroke; kept until the dictionary changes. */
    private val matchCache = HashMap<String, Pair<PinyinDictionary.Word, Float>?>()
    private var cachedFor: PinyinDictionary? = null
    private var cachedVersion = -1

    private fun bestMatch(run: List<Piece>, dictionary: PinyinDictionary): Pair<PinyinDictionary.Word, Float>? {
        if (cachedFor !== dictionary || cachedVersion != dictionary.version || matchCache.size > MAX_CACHE) {
            matchCache.clear()
            cachedFor = dictionary
            cachedVersion = dictionary.version
        }
        val key = run.joinToString(" ") { if (it.isPrefix) "${it.text}*" else it.text }
        return matchCache.getOrPut(key) {
            // Fuzzy sounds let shan find 上 (shang); a word spelled the way it was typed comes first
            // unless the other is much commoner. The score returned already carries that cost.
            dictionary.scoredMatches(run.map { PinyinDictionary.Piece(it.text, it.isPrefix) }, FUZZY_CHOICES)
                .map { (word, score) ->
                    val fuzzy = run.indices.count { i -> !run[i].isPrefix && word.syllables.getOrNull(i) != run[i].text }
                    word to score - FUZZY_COST * fuzzy
                }
                .maxByOrNull { it.second }
        }
    }

    private fun relax(best: Array<Step?>, at: Int, step: Step) {
        val current = best[at]
        if (current == null || step.cost < current.cost) best[at] = step
    }

    /**
     * The pieces starting at [start]: whole syllables, the same with one slip, 简拼 initials,
     * and, at the very end of the input, the start of a syllable still being typed.
     */
    private fun readPieces(input: String, start: Int, dictionary: PinyinDictionary): List<Piece> {
        val result = ArrayList<Piece>()
        val seen = HashSet<String>()
        fun add(end: Int, text: String, isPrefix: Boolean, cost: Float) {
            if (seen.add("$end/$text/$isPrefix")) result += Piece(end, text, isPrefix, cost)
        }
        var limit = start
        while (limit < input.length && limit - start < MAX_SYLLABLE_LENGTH + 1 && input[limit] != PinyinEngine.SEPARATOR) limit++
        for (end in start + 1..limit) {
            val typed = input.substring(start, end)
            if (typed in dictionary.rawSyllables) add(end, typed, isPrefix = false, cost = 0f)
            if (end == input.length && typed.length >= 2 && typed !in dictionary.rawSyllables && dictionary.isSyllablePrefix(typed)) {
                add(end, typed, isPrefix = true, cost = STILL_TYPING_COST)
            }
            // Two letters are too few to tell a slip from 简拼 (hs is h + s, not he).
            if (typed.length >= 3 || typed.length == 2 && typed.endsWith("n")) {
                for ((fixed, cost) in corrections(typed, dictionary)) add(end, fixed, isPrefix = false, cost = cost)
            }
        }
        // 简拼: a lone initial (zh, ch, sh count as one) stands for any syllable it starts.
        // An initial right before a vowel is rarely 简拼: "xang" is a slip for xiang, not x + ang.
        val first = input[start]
        if (first in INITIALS) {
            fun costBefore(next: Int) = if (input.getOrNull(next)?.let { it in VOWELS } == true) INITIAL_BEFORE_VOWEL_COST else INITIAL_COST
            val two = input.substring(start, minOf(start + 2, input.length))
            if (two in COMPOUND_INITIALS) add(start + 2, two, isPrefix = true, cost = costBefore(start + 2))
            add(start + 1, "$first", isPrefix = true, cost = costBefore(start + 1))
        }
        return result
    }

    /** Syllables one slip away from [typed], with what that slip costs. */
    private fun corrections(typed: String, dictionary: PinyinDictionary): List<Pair<String, Float>> {
        val syllables = dictionary.rawSyllables
        val result = ArrayList<Pair<String, Float>>()
        fun consider(candidate: String, cost: Float) {
            if (candidate != typed && candidate in syllables) result += candidate to cost
        }
        // Front and back nasals, the commonest slip: in/ing, an/ang, en/eng.
        if (typed.endsWith("n")) consider(typed + "g", NASAL_COST)
        if (typed.endsWith("ng")) consider(typed.dropLast(1), NASAL_COST)
        // A missing letter (xang → xiang), the commonest slip when typing fast.
        for (at in 0..typed.length) {
            for (letter in 'a'..'z') consider(typed.substring(0, at) + letter + typed.substring(at), MISSING_LETTER_COST)
        }
        // An extra letter.
        for (at in typed.indices) consider(typed.removeRange(at, at + 1), SLIP_COST)
        // A neighbouring key (xiamg → xiang).
        for (at in typed.indices) {
            for (neighbour in NEIGHBOURS[typed[at]].orEmpty()) {
                consider(typed.substring(0, at) + neighbour + typed.substring(at + 1), SLIP_COST)
            }
        }
        // Two letters swapped (xinag → xiang).
        for (at in 0 until typed.length - 1) {
            val swapped = StringBuilder(typed).apply {
                val a = this[at]
                this[at] = this[at + 1]
                this[at + 1] = a
            }.toString()
            consider(swapped, SLIP_COST)
        }
        return result.distinctBy { it.first }
    }

    private const val MAX_INPUT = 60
    private const val MAX_NINE_KEY_WORD_DIGITS = 24
    private const val MAX_CACHE = 20_000
    private const val FUZZY_CHOICES = 8
    /** Per syllable that only matches through a fuzzy rule (shan for shang). */
    private const val FUZZY_COST = 2.5f
    private const val MAX_SYLLABLE_LENGTH = 6
    private const val MAX_WORD_SYLLABLES = 6

    /** Per word: above any word's score, so a word always costs something and fewer words win. */
    private const val WORD_COST = 22f
    private const val INITIAL_COST = 2.5f
    private const val INITIAL_BEFORE_VOWEL_COST = 9f
    private const val VOWELS = "aoe"
    private const val STILL_TYPING_COST = 1.5f
    private const val NASAL_COST = 3f
    private const val MISSING_LETTER_COST = 5f
    private const val SLIP_COST = 7f

    private const val INITIALS = "bpmfdtnlgkhjqxzcsryw"
    private val COMPOUND_INITIALS = setOf("zh", "ch", "sh")

    /** Keys next to each other on a QWERTY keyboard. */
    private val NEIGHBOURS: Map<Char, String> = run {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val map = HashMap<Char, StringBuilder>()
        for ((r, row) in rows.withIndex()) {
            for ((c, key) in row.withIndex()) {
                val near = map.getOrPut(key) { StringBuilder() }
                row.getOrNull(c - 1)?.let(near::append)
                row.getOrNull(c + 1)?.let(near::append)
                // The rows are offset by about half a key.
                rows.getOrNull(r - 1)?.let { above -> above.getOrNull(c)?.let(near::append); above.getOrNull(c + 1)?.let(near::append) }
                rows.getOrNull(r + 1)?.let { below -> below.getOrNull(c)?.let(near::append); below.getOrNull(c - 1)?.let(near::append) }
            }
        }
        map.mapValues { it.value.toString() }
    }
}
