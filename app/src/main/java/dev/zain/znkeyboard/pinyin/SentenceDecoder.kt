package dev.zain.znkeyboard.pinyin

import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln

/**
 * Whole-sentence input: finds the best way to read all of the typed pinyin as a row of
 * dictionary words, so "womingtianxiangqupashan" becomes 我明天想去爬山 in one candidate.
 *
 * Every split is tried: whole syllables, 简拼 initials ("ymy" for 有没有), mixes of the two, and
 * syllables with one typing slip (min → ming, xang → xiang, xiamg → xiang, xinag → xiang); slips
 * and initials cost extra, so a correct spelling is never "fixed".
 *
 * Without a language model each word costs a fixed amount less its score (log weight plus what
 * the user picked), so fewer, commoner words win. With one ([PinyinDictionary.languageModel]),
 * a word costs less the likelier it is after the word before it, and the search keeps the best
 * few readings ending in different words at each position (a beam), so context can pick
 * 两点 over 亮点 or 四川菜 over 四川才.
 *
 * When the keyboard passes how well each tap fitted each key ([Taps]), a typed letter replaced by
 * another costs little if the tap landed near that key's edge and a lot if it hit the typed key
 * squarely, instead of a flat cost for any QWERTY neighbour. Pure Kotlin and main-thread only,
 * like [PinyinDictionary].
 */
object SentenceDecoder {
    /**
     * The best reading of [input], or null when it can't be read whole. [parts] are the words,
     * for learning; [spelling] is the pinyin as read, slips corrected ("wo ming tian y m y").
     */
    class Sentence(val text: String, val parts: List<PinyinEngine.Candidate>, val spelling: String, val cost: Float)

    /** One way to read input[start, end): a whole syllable (possibly corrected) or the start of one. */
    private class Piece(val end: Int, val text: String, val isPrefix: Boolean, val cost: Float) {
        /** A syllable that differs from what was typed. */
        val slip get() = !isPrefix && cost > 0f
    }

    /** A word over a run of pieces: [cost] is what the pieces and fuzzy sounds add to the word's own. */
    private class Edge(val end: Int, val pieces: List<Piece>, val match: PinyinDictionary.Match, val cost: Float)

    /**
     * A reading up to some position, ending in [word] (null at the start and after a separator);
     * [context] is the last word's model id and [text] its text, carried across separators.
     */
    private class Step(
        val cost: Float,
        val previous: Step?,
        val word: PinyinDictionary.Word?,
        val pieces: List<Piece>,
        val context: Int,
        val text: String?,
    ) {
        /** Worse ways to the same place and last word, best first: what the other readings differ by. */
        var alternates: ArrayList<Step>? = null

        fun addAlternate(step: Step) {
            val list = alternates ?: ArrayList<Step>(MAX_ALTERNATES + 1).also { alternates = it }
            var at = list.size
            while (at > 0 && list[at - 1].cost > step.cost) at--
            list.add(at, step)
            if (list.size > MAX_ALTERNATES) list.removeAt(MAX_ALTERNATES)
        }
    }

    /** A whole reading: its [steps] from last to first, and [from], the first it may still change. */
    private class Reading(val cost: Float, val steps: List<Step>, val from: Int)

    /** The word committed just before this input, so its first word is read in context. */
    class Context(val text: String, val id: Int)

    /**
     * Per input position, -ln P(tap | key) for each letter a–z less the best key's (0 for the best,
     * +∞ for keys too far away), or null for a position without a tap (separators, pasted text).
     */
    fun interface Taps {
        fun costs(at: Int): FloatArray?
    }

    fun decode(input: String, dictionary: PinyinDictionary, taps: Taps? = null, before: Context? = null): Sentence? =
        decodeAll(input, dictionary, taps, before, 1).firstOrNull()

    /** The best [limit] different readings of [input], best first; see [decode]. */
    fun decodeAll(input: String, dictionary: PinyinDictionary, taps: Taps?, before: Context?, limit: Int): List<Sentence> {
        val n = input.length
        if (n == 0 || n > MAX_INPUT) return emptyList()
        val model = dictionary.languageModel
        val beam = if (model == null) 1 else BEAM
        val choices = if (model == null) 1 else LM_CHOICES
        val piecesAt = arrayOfNulls<List<Piece>>(n)
        fun pieces(at: Int): List<Piece> = piecesAt[at] ?: readPieces(input, at, dictionary, taps).also { piecesAt[at] = it }

        fun wordCost(edge: Edge, step: Step): Float = edge.cost +
            if (model == null) WORD_COST - edge.match.score else modelCost(edge.match, step, dictionary, model)

        // Each letter its own character, one word: 时候 for sh, 解决方案 for jjfa. Its letters count
        // as one word's worth of 简拼, or a long word could never beat a few short ones.
        val wholeInitials = model != null && n in 2..MAX_INITIALS_WORD && PinyinEngine.SEPARATOR !in input &&
            !PinyinEngine.isWholePinyin(input, dictionary)
        val best = Array(n + 1) { ArrayList<Step>(beam) }
        val startContext = before?.id?.takeIf { it >= 0 && model != null } ?: model?.start ?: -1
        best[0] += Step(0f, null, null, emptyList(), startContext, before?.text)
        for (start in 0 until n) {
            val here = best[start]
            if (here.isEmpty()) continue
            if (input[start] == PinyinEngine.SEPARATOR) {
                for (step in here) relax(best[start + 1], Step(step.cost, step, null, emptyList(), step.context, step.text), beam)
                continue
            }
            // Runs of pieces from here, as long as some word's initials still start that way. A
            // separator only splits syllables, so a word may run across it (xi'an → 西安).
            val edges = ArrayList<Edge>()
            fun extend(at: Int, run: MutableList<Piece>, initials: String) {
                if (run.isNotEmpty()) {
                    val pieceCost = run.sumOf { it.cost.toDouble() }.toFloat()
                    for ((match, fuzzyCost) in bestMatches(run, dictionary, choices)) {
                        edges += Edge(at, run.toList(), match, pieceCost + fuzzyCost)
                    }
                }
                if (run.size >= MAX_WORD_SYLLABLES || at >= n) return
                if (input[at] == PinyinEngine.SEPARATOR) {
                    if (run.isNotEmpty()) extend(at + 1, run, initials)
                    return
                }
                for (piece in pieces(at)) {
                    // One slip per word: the readings would multiply with no likelier word to show.
                    if (piece.slip && run.any { it.slip }) continue
                    val next = if (initials.isEmpty()) "${piece.text[0]}" else "$initials ${piece.text[0]}"
                    if (!dictionary.hasInitialsPrefix(next)) continue
                    run += piece
                    extend(piece.end, run, next)
                    run.removeAt(run.lastIndex)
                }
            }
            extend(start, ArrayList(), "")
            if (start == 0 && wholeInitials) {
                val letters = input.indices.map { Piece(it + 1, input[it].toString(), isPrefix = true, cost = 0f) }
                for ((match, _) in bestMatches(letters, dictionary, choices)) edges += Edge(n, letters, match, INITIAL_COST)
            }
            for (step in here) {
                for (edge in edges) {
                    val target = best[edge.end]
                    // -ln P is never negative, so a word costs at least this much: skip the lookup
                    // when even that can't make the beam.
                    if (model != null && target.size >= beam &&
                        target.last().cost <= step.cost + LM_WORD_COST + edge.cost
                    ) continue
                    val word = edge.match.word
                    relax(target, Step(step.cost + wordCost(edge, step), step, word, edge.pieces, word.id, word.text), beam)
                }
            }
        }

        return bestReadings(best[n], model, limit).mapNotNull { reading ->
            val steps = reading.steps.asReversed().filter { it.word != null }
            if (steps.isEmpty()) return@mapNotNull null
            val parts = steps.map { step -> step.word!!.let { PinyinEngine.Candidate(it.text, n, it.syllables, id = it.id) } }
            val spelling = steps.joinToString(" ") { step -> step.pieces.joinToString(" ") { it.text } }
            Sentence(parts.joinToString("") { it.text }, parts, spelling, reading.cost)
        }
    }

    /**
     * 9-key digits (no separators or locked syllables) read as a sentence: each word is a run of
     * digits that is its whole code or its 简拼. Ranked like [decode]: with a language model, by
     * context, so 5422468 reads 家丑 and not 家抽, the commoner characters.
     */
    fun decodeNineKey(digits: String, dictionary: PinyinDictionary): Sentence? =
        decodeAllNineKey(digits, dictionary, 1).firstOrNull()?.takeIf { it.parts.size >= 2 }

    /** The best [limit] different readings of [digits], best first; see [decodeNineKey]. */
    fun decodeAllNineKey(digits: String, dictionary: PinyinDictionary, limit: Int): List<Sentence> {
        val n = digits.length
        if (n < 2 || n > MAX_INPUT) return emptyList()
        val model = dictionary.languageModel
        val beam = if (model == null) 1 else BEAM
        val choices = if (model == null) 1 else NINE_KEY_LM_CHOICES
        val best = Array(n + 1) { ArrayList<Step>(beam) }
        best[0] += Step(0f, null, null, emptyList(), model?.start ?: -1, null)
        for (start in 0 until n) {
            val here = best[start]
            if (here.isEmpty()) continue
            for (end in start + 1..minOf(n, start + MAX_NINE_KEY_WORD_DIGITS)) {
                val found = dictionary.nineKeyWords(digits.substring(start, end), choices)
                if (found.isEmpty()) continue
                val target = best[end]
                for (step in here) {
                    for (candidate in found) {
                        val match = candidate.match
                        val extra = if (candidate.byInitials) INITIAL_COST * match.word.syllables.size else 0f
                        // As in decode: skip the lookup when even a certain word can't make the beam.
                        if (model != null && target.size >= beam && target.last().cost <= step.cost + LM_WORD_COST + extra) continue
                        val cost = step.cost + extra + if (model == null) WORD_COST - match.score else modelCost(match, step, dictionary, model)
                        relax(target, Step(cost, step, match.word, emptyList(), match.word.id, match.word.text), beam)
                    }
                }
            }
        }
        return bestReadings(best[n], model, limit).map { reading ->
            val words = reading.steps.asReversed().mapNotNull { it.word }
            val parts = words.map { PinyinEngine.Candidate(it.text, n, it.syllables) }
            Sentence(words.joinToString("") { it.text }, parts, words.flatMap { it.syllables }.joinToString(" "), reading.cost)
        }
    }

    /**
     * The best [limit] readings with different text among those ending in [ends], counting how
     * likely a sentence is to end there. Each is the best way to its last word, or one with a step
     * swapped for an alternate, which ends in the same word, so what follows costs the same and
     * the swap adds exactly the difference.
     */
    private fun bestReadings(ends: List<Step>, model: PinyinLanguageModel?, limit: Int): List<Reading> {
        fun trail(step: Step): List<Step> = generateSequence(step) { it.previous }.toList()
        val queue = PriorityQueue<Reading>(compareBy { it.cost })
        for (step in ends) {
            val end = if (model != null && step.context >= 0) -LM_WEIGHT * model.logP(step.context, model.end) else 0f
            queue += Reading(step.cost + end, trail(step), 0)
        }
        val worst = (queue.peek()?.cost ?: return emptyList()) + ALTERNATIVE_MARGIN
        val result = ArrayList<Reading>(limit)
        val seen = HashSet<String>()
        var tried = 0
        while (result.size < limit && tried++ < MAX_READINGS_TRIED) {
            val reading = queue.poll() ?: break
            if (reading.cost > worst) break
            if (seen.add(reading.steps.joinToString("") { it.word?.text.orEmpty() })) result += reading
            if (result.size == limit) break
            for (at in reading.from until reading.steps.size) {
                val step = reading.steps[at]
                step.alternates?.forEach { other ->
                    queue += Reading(reading.cost + other.cost - step.cost, reading.steps.subList(0, at) + trail(other), at)
                }
            }
        }
        return result
    }

    /** What [match] costs after [step] with a language model: less the likelier it is there. */
    private fun modelCost(match: PinyinDictionary.Match, step: Step, dictionary: PinyinDictionary, model: PinyinLanguageModel): Float {
        val id = match.word.id
        var logP = if (id >= 0) model.logP(step.context, id) else match.score - match.learned - dictionary.logTotalWeight
        // What the user picks is a second, personal model mixed in: how often they chose this
        // word, and this word after the last one. Probabilities, so however often a word was
        // picked it costs something, and its characters can't outbid it one by one.
        val learnedUses = dictionary.learnedUses
        val pairTotal = step.text?.let(dictionary::pairTotal) ?: 0
        if (learnedUses > 0 || pairTotal > 0) {
            var keep = 1f
            var personal = 0f
            if (learnedUses > 0) {
                keep -= PICKS_WEIGHT
                personal += PICKS_WEIGHT * match.uses / learnedUses
            }
            if (pairTotal > 0) {
                keep -= PAIRS_WEIGHT
                personal += PAIRS_WEIGHT * dictionary.pairUses(step.text!!, match.word.text) / pairTotal
            }
            logP = ln(keep * exp(logP) + personal)
        }
        // Trade words are rarer on the web than in this keyboard's users' messages.
        if (match.word.trade) logP = minOf(logP + TRADE_BONUS, 0f)
        return LM_WORD_COST - LM_WEIGHT * logP
    }

    /** What [match] costs right after [before], lower when likelier there; see [modelCost]. */
    fun wordCostAfter(match: PinyinDictionary.Match, before: Context?, dictionary: PinyinDictionary): Float {
        val model = dictionary.languageModel ?: return -match.score
        val context = before?.id?.takeIf { it >= 0 } ?: model.start
        return modelCost(match, Step(0f, null, null, emptyList(), context, before?.text), dictionary, model)
    }

    /** Lookups repeat from keystroke to keystroke; kept until the dictionary changes. */
    private val matchCache = HashMap<String, List<Pair<PinyinDictionary.Match, Float>>>()
    private var cachedFor: PinyinDictionary? = null
    private var cachedVersion = -1

    /** The best [limit] words for [run], best first, each with what its fuzzy sounds cost. */
    private fun bestMatches(run: List<Piece>, dictionary: PinyinDictionary, limit: Int): List<Pair<PinyinDictionary.Match, Float>> {
        if (cachedFor !== dictionary || cachedVersion != dictionary.version || matchCache.size > MAX_CACHE) {
            matchCache.clear()
            cachedFor = dictionary
            cachedVersion = dictionary.version
        }
        val key = run.joinToString(" ") { if (it.isPrefix) "${it.text}*" else it.text } + "/$limit"
        return matchCache.getOrPut(key) {
            // Fuzzy sounds let shan find 上 (shang); a word spelled the way it was typed comes first
            // unless the other is much commoner.
            dictionary.scoredMatches(run.map { PinyinDictionary.Piece(it.text, it.isPrefix) }, maxOf(FUZZY_CHOICES, limit))
                .map { match ->
                    val fuzzy = run.indices.count { i -> !run[i].isPrefix && match.word.syllables.getOrNull(i) != run[i].text }
                    match to FUZZY_COST * fuzzy
                }
                .sortedBy { (match, fuzzyCost) -> fuzzyCost - match.score }
                .take(limit)
        }
    }

    /** Adds [step] to [steps], the best [beam] readings at one position, one per last word. */
    private fun relax(steps: ArrayList<Step>, step: Step, beam: Int) {
        val same = steps.indexOfFirst { it.context == step.context && (step.context >= 0 || it.word?.text == step.word?.text) }
        if (same >= 0) {
            val kept = steps[same]
            if (kept.cost <= step.cost) {
                kept.addAlternate(step)
                return
            }
            step.addAlternate(kept)
            kept.alternates?.forEach(step::addAlternate)
            kept.alternates = null
            steps.removeAt(same)
        } else if (steps.size >= beam) {
            if (steps.last().cost <= step.cost) return
            steps.removeAt(steps.lastIndex)
        }
        var at = steps.size
        while (at > 0 && steps[at - 1].cost > step.cost) at--
        steps.add(at, step)
    }

    /**
     * The pieces starting at [start]: whole syllables, the same with one slip, 简拼 initials,
     * and, at the very end of the input, the start of a syllable still being typed.
     */
    private fun readPieces(input: String, start: Int, dictionary: PinyinDictionary, taps: Taps?): List<Piece> {
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
            // Two letters are too few to tell a slip from 简拼 (hs is h + s, not he), unless the taps
            // say a key was nearly missed (wl → wo, with l just below o).
            val tapAt = { at: Int -> taps?.costs(start + at) }
            // A vowel starting a syllable may have lost its y or w (ue → yue, i → yi); two letters
            // with a vowel that are no syllable were never 简拼 (ho → hao).
            val lostInitial = typed.length <= 2 && typed[0] in ZERO_INITIAL_VOWELS
            val noSyllable = typed.length == 2 && typed !in dictionary.rawSyllables && typed.any { it in ZERO_INITIAL_VOWELS }
            if (typed.length >= 3 || typed.length == 2 && typed.endsWith("n") || lostInitial || noSyllable) {
                for ((fixed, cost) in corrections(typed, dictionary, tapAt)) add(end, fixed, isPrefix = false, cost = cost)
            } else if (typed.length == 2 && taps != null) {
                for ((fixed, cost) in corrections(typed, dictionary, tapAt, tapsOnly = true)) {
                    add(end, fixed, isPrefix = false, cost = cost + SHORT_TOUCH_SLIP_COST)
                }
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

    /** Syllables one slip away from [typed], with what that slip costs; [tapAt] gives each letter's tap. */
    private fun corrections(
        typed: String,
        dictionary: PinyinDictionary,
        tapAt: (Int) -> FloatArray?,
        tapsOnly: Boolean = false,
    ): List<Pair<String, Float>> {
        val syllables = dictionary.rawSyllables
        val result = ArrayList<Pair<String, Float>>()
        fun consider(candidate: String, cost: Float) {
            if (candidate != typed && candidate in syllables) result += candidate to cost
        }
        if (tapsOnly) {
            addTapSlips(typed, tapAt, ::consider)
            return result.sortedBy { it.second }.distinctBy { it.first }
        }
        // A syllable that should have started with y or w, which pinyin writes before a vowel.
        if (typed[0] in ZERO_INITIAL_VOWELS) {
            consider("y$typed", LOST_INITIAL_COST)
            consider("w$typed", LOST_INITIAL_COST)
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
        // Another key than the one typed (xiamg → xiang): as likely as the tap says, or any
        // QWERTY neighbour at a flat cost where there was no tap.
        addTapSlips(typed, tapAt, ::consider)
        for (at in typed.indices) {
            if (tapAt(at) != null && typed[at] in 'a'..'z') continue
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
        return result.sortedBy { it.second }.distinctBy { it.first }
    }

    private const val TRADE_BONUS = 2.5f
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

    /** With a language model: readings kept per position, and words tried per run of pieces. */
    private const val BEAM = 6
    private const val LM_CHOICES = 8
    /** A 9-key code spells many words (each digit is three or four letters), so more per run. */
    private const val NINE_KEY_LM_CHOICES = 16
    /** Worse ways kept per reading in the beam, to build other readings from. */
    private const val MAX_ALTERNATES = 3
    /** Other readings must stay this close to the best, and the search for them this short. */
    private const val ALTERNATIVE_MARGIN = 6f
    private const val MAX_READINGS_TRIED = 24
    /** With a language model, per word on top of its -ln P; favours fewer, longer words. */
    private const val LM_WORD_COST = 1f
    private const val LM_WEIGHT = 0.6f
    /** Shares of the personal models in a word's probability: the user's picks, and their pairs. */
    private const val PICKS_WEIGHT = 0.05f
    private const val PAIRS_WEIGHT = 0.1f
    private const val INITIAL_COST = 2.5f
    private const val MAX_INITIALS_WORD = 6
    private const val INITIAL_BEFORE_VOWEL_COST = 9f
    private const val VOWELS = "aoe"
    private const val STILL_TYPING_COST = 1.5f
    private const val NASAL_COST = 3f
    private const val MISSING_LETTER_COST = 3.5f
    private const val LOST_INITIAL_COST = 5f
    private const val ZERO_INITIAL_VOWELS = "aeiou"
    private const val SLIP_COST = 7f
    /** A key swapped for another, given the tap: this plus how much worse the tap fits that key. */
    private const val TOUCH_SLIP_COST = 2f
    private const val TOUCH_WEIGHT = 1f
    private const val MAX_TOUCH_EXTRA = 4f
    /** On top, for a two-letter piece, which could as well be two 简拼 initials. */
    private const val SHORT_TOUCH_SLIP_COST = 1f
    private const val LETTERS = 26

    /** Each letter of [typed] with a tap, swapped for another key at what the tap says that costs. */
    private inline fun addTapSlips(typed: String, tapAt: (Int) -> FloatArray?, consider: (String, Float) -> Unit) {
        for (at in typed.indices) {
            val costs = tapAt(at) ?: continue
            val own = typed[at] - 'a'
            if (own !in 0 until LETTERS) continue
            for (letter in 0 until LETTERS) {
                val cost = costs[letter]
                if (letter == own || cost.isInfinite()) continue
                val extra = (cost - costs[own]).coerceAtLeast(0f)
                // Keys the tap was nowhere near would cost more than they could ever win back.
                if (extra > MAX_TOUCH_EXTRA) continue
                consider(typed.substring(0, at) + ('a' + letter) + typed.substring(at + 1), TOUCH_SLIP_COST + TOUCH_WEIGHT * extra)
            }
        }
    }

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
