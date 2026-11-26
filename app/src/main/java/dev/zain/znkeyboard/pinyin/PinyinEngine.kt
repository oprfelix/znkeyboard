package dev.zain.znkeyboard.pinyin

import java.util.Collections
import kotlin.math.ln

/**
 * Turns typed pinyin letters into candidates, longest match first, each group ranked by the
 * dictionary (weights plus what the user picked before). Full pinyin, 简拼 (initials such as
 * "nh" for 你好) and mixes of the two ("nhao") all work.
 */
object PinyinEngine {
    /** A piece cut from the input; [end] is its exclusive end offset in the raw input. */
    data class Syllable(val text: String, val end: Int)

    /**
     * [consumed] is how many input characters picking this candidate uses up; [syllables] is
     * its raw pinyin, one per character, for learning. A whole sentence also has its [parts]
     * (the words, learned one by one) and its [spelling] as read, slips corrected. [id] is the
     * dictionary word's (see [PinyinDictionary.Word.id]).
     */
    data class Candidate(
        val text: String,
        val consumed: Int,
        val syllables: List<String>,
        val parts: List<Candidate>? = null,
        val spelling: String? = null,
        val id: Int = -1,
    )

    const val SEPARATOR = '\''
    private const val MAX_SYLLABLE_LENGTH = 6
    private const val MAX_INITIALS_LENGTH = 6
    private const val ALTERNATIVES_LIMIT = 20
    private const val INITIAL_WORDS_LIMIT = 6
    private const val MIN_BEFORE_INITIALS = 3
    private const val MAX_BEFORE_INITIALS = 5
    private const val MIN_LETTERS_NEAR_TOP = 3
    private const val MAX_CANDIDATES = 80
    private const val FULL_MATCH_LIMIT = 30
    private const val PARTIAL_MATCH_LIMIT = 8
    private const val SINGLE_SYLLABLE_LIMIT = 40
    /** Readings of the whole input to offer: the best, and others that stay close to it. */
    const val MAX_SENTENCES = 3
    /**
     * With a [SentenceScorer], the decoder's best this many readings are scored and reordered by
     * -cost + RERANK_MODEL_WEIGHT × ln P + RERANK_CHAR_BONUS × characters (the language model
     * favours short readings). Tuned on the benchmark's even half (tools/pinyin/charlm).
     */
    const val RERANK_READINGS = 8
    private const val RERANK_MODEL_WEIGHT = 0.5f
    private const val RERANK_CHAR_BONUS = 3f

    /** Scores whole-input readings: ln P of each as a clause, or null to leave them as decoded. */
    fun interface SentenceScorer {
        fun score(texts: List<String>): FloatArray?
    }
    /** Other ways to split the input: only for inputs this short, at most this many splits, their
     * best words, placed after the first few candidates. */
    private const val MAX_SPLIT_LETTERS = 8
    private const val MAX_SPLITS = 4
    private const val WORDS_PER_SPLIT = 2
    private const val OTHER_SPLITS_AT = 3
    /** Common words only (ln of a dictionary weight): 余额 and 西安, not 积案 or 堤岸. A word the
     * user has picked gains from learning and can pass. */
    private val MIN_SPLIT_WORD_SCORE = ln(20000.0).toFloat()

    /**
     * Splits [input] into pieces, longest first, backtracking when the rest would not parse.
     * A piece is a whole syllable or the start of one, so "nh" splits as n + h. Stops at the
     * first part that does not parse, so a typo leaves the remaining letters uncommitted.
     */
    fun segment(input: String, dictionary: PinyinDictionary): List<Syllable> {
        val result = mutableListOf<Syllable>()
        var start = 0
        while (start < input.length) {
            if (input[start] == SEPARATOR) {
                start++
                continue
            }
            var end = start
            while (end < input.length && input[end] != SEPARATOR) end++
            val chunk = segmentChunk(input, start, end, dictionary)
            result += chunk
            if (chunk.lastOrNull()?.end != end) break
            start = end
        }
        return result
    }

    private fun segmentChunk(input: String, start: Int, end: Int, dictionary: PinyinDictionary): List<Syllable> {
        val best = mutableListOf<Syllable>()
        // Positions from which the rest cannot be parsed; avoids exponential backtracking.
        val deadEnds = HashSet<Int>()

        fun search(position: Int, path: MutableList<Syllable>): Boolean {
            if (position == end) {
                best.clear()
                best += path
                return true
            }
            if (position in deadEnds) return false
            for (length in minOf(MAX_SYLLABLE_LENGTH, end - position) downTo 1) {
                val piece = input.substring(position, position + length)
                if (dictionary.isSyllable(piece) || dictionary.isSyllablePrefix(piece)) {
                    path += Syllable(piece, position + length)
                    if (search(position + length, path)) return true
                    path.removeAt(path.lastIndex)
                }
            }
            val reached = path.lastOrNull()?.end ?: start
            val bestReached = best.lastOrNull()?.end ?: start
            if (reached > bestReached) {
                best.clear()
                best += path
            }
            deadEnds += position
            return false
        }

        search(start, mutableListOf())
        return best
    }

    /**
     * A whole syllable of two or more letters must match exactly; single letters and
     * half-typed syllables are 简拼 and match any syllable they start. The final piece of the
     * input may always still be growing, so it matches as a prefix too.
     */
    private fun piecesFor(syllables: List<Syllable>, count: Int, dictionary: PinyinDictionary): List<PinyinDictionary.Piece> =
        (0 until count).map { i ->
            val text = syllables[i].text
            val complete = text.length >= 2 && dictionary.isSyllable(text)
            val stillTyping = i == syllables.lastIndex
            PinyinDictionary.Piece(text, isPrefix = !complete || stillTyping)
        }

    /**
     * The whole input read as one sentence (when that takes more than one word, reads past a
     * slip, or the language model prefers another word than the dictionary's first), then the
     * words for its leading part, with the next best sentences after the words that read it all.
     */
    /**
     * [before] is the word committed just before, so the sentence reads on from it. [scorer], when
     * given, reorders the readings of the whole input (see [RERANK_READINGS]).
     */
    fun candidates(
        input: String,
        dictionary: PinyinDictionary,
        taps: SentenceDecoder.Taps? = null,
        before: SentenceDecoder.Context? = null,
        scorer: SentenceScorer? = null,
    ): List<Candidate> = withOtherSplits(input, rankedCandidates(input, dictionary, taps, before, scorer), dictionary)

    /** A detached first-decode result. It retains no dictionary, taps, or mutable IME state. */
    class PreparedCandidates internal constructor(
        val input: String,
        val readings: List<ReadingSnapshot>,
        private val words: List<Candidate>,
        private val initials: List<Candidate>,
        private val wholePinyin: Boolean,
        private val hasLanguageModel: Boolean,
        private val otherSplits: List<Candidate>,
        val acceptsScores: Boolean,
    ) {
        val texts: List<String> = frozen(readings.map { it.text })

        /** Rebuilds only the small candidate ordering, never the dictionary or sentence decoder. */
        fun candidates(scores: FloatArray? = null): List<Candidate> {
            val ordered = if (acceptsScores) {
                (if (scores?.size == readings.size) {
                    readings.indices.sortedByDescending { at ->
                        -readings[at].cost + RERANK_MODEL_WEIGHT * scores[at] + RERANK_CHAR_BONUS * readings[at].text.length
                    }.map(readings::get)
                } else readings).take(MAX_SENTENCES)
            } else readings
            val ranked = if (ordered.isEmpty() ||
                (!hasLanguageModel && ordered.first().parts.size < 2 && ordered.first().spelling.replace(" ", "") == input.replace(SEPARATOR.toString(), ""))
            ) words else withSentences(ordered.map { it.candidate }, words, input.length)
            val withInitials = addInitialWords(input, ranked, initials, wholePinyin, hasLanguageModel)
            return frozen(addOtherSplits(withInitials, otherSplits))
        }
    }

    /** Candidate and score metadata retained for AI ordering; every nested list is frozen. */
    class ReadingSnapshot internal constructor(
        val text: String,
        val cost: Float,
        val spelling: String,
        val parts: List<Candidate>,
        val candidate: Candidate,
    )

    /** The AI callback's value-only identity; it never retains a mutable dictionary or IME target. */
    data class AiSnapshotIdentity(
        val session: Long,
        val lifecycle: Long,
        val generation: Long,
        val composing: String,
        val dictionaryVersion: Int,
    ) {
        fun isCurrent(session: Long, lifecycle: Long, generation: Long, composing: String, dictionaryVersion: Int): Boolean =
            this.session == session && this.lifecycle == lifecycle && this.generation == generation &&
                this.composing == composing && this.dictionaryVersion == dictionaryVersion
    }

    /** Main-thread application gate, expressed as values so races can be tested without an IME. */
    data class AiApplyState(
        val session: Long,
        val lifecycle: Long,
        val generation: Long,
        val composing: String,
        val dictionaryVersion: Int,
        val baseDelivered: Boolean,
        val candidatesCurrent: Boolean,
        val scorerCurrent: Boolean,
        val qwerty: Boolean,
        val choosing: Boolean,
        val fixing: Boolean,
        val editing: Boolean,
        val active: Boolean,
        val destroyed: Boolean,
    )

    fun mayApplyAi(identity: AiSnapshotIdentity, state: AiApplyState): Boolean =
        !state.destroyed && state.active && state.baseDelivered && state.candidatesCurrent && state.scorerCurrent &&
            state.qwerty && !state.choosing && !state.fixing && !state.editing &&
            identity.isCurrent(state.session, state.lifecycle, state.generation, state.composing, state.dictionaryVersion)

    private fun <T> frozen(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))

    private fun frozenCandidate(candidate: Candidate): Candidate = candidate.copy(
        syllables = frozen(candidate.syllables),
        parts = candidate.parts?.let { frozen(it.map(::frozenCandidate)) },
    )

    /** The bar renders text order and a fix control when the first candidate has multiple parts. */
    fun visibleOrderChanged(previous: List<Candidate>, next: List<Candidate>): Boolean =
        previous.size != next.size || previous.firstOrNull()?.parts?.size != next.firstOrNull()?.parts?.size ||
            previous.indices.any { previous[it].text != next[it].text }

    /** Performs the one full decode and all dictionary lookups before handing data to the AI worker. */
    fun prepareCandidates(
        input: String,
        dictionary: PinyinDictionary,
        taps: SentenceDecoder.Taps? = null,
        before: SentenceDecoder.Context? = null,
        aiEnabled: Boolean = false,
    ): PreparedCandidates {
        val words = frozen(wordCandidates(input, dictionary).map(::frozenCandidate))
        val rerank = aiEnabled && dictionary.languageModel != null
        val limit = if (dictionary.languageModel == null) 1 else if (rerank) RERANK_READINGS else MAX_SENTENCES
        val readings = frozen(SentenceDecoder.decodeAll(input, dictionary, taps, before, limit).map { sentence ->
            val candidate = frozenCandidate(wholeInput(sentence, input.length))
            ReadingSnapshot(sentence.text, sentence.cost, sentence.spelling, candidate.parts.orEmpty(), candidate)
        })
        val initials = frozen(initialCandidates(input, dictionary, before).map(::frozenCandidate))
        val other = frozen(otherSplitCandidates(input, dictionary).map(::frozenCandidate))
        return PreparedCandidates(input, readings, words, initials, initials.isNotEmpty() && isWholePinyin(input, dictionary), dictionary.languageModel != null, other, rerank && readings.size > 1)
    }

    private fun rankedCandidates(
        input: String,
        dictionary: PinyinDictionary,
        taps: SentenceDecoder.Taps?,
        before: SentenceDecoder.Context?,
        scorer: SentenceScorer?,
    ): List<Candidate> {
        val words = wordCandidates(input, dictionary)
        val rerank = scorer != null && dictionary.languageModel != null
        val limit = if (dictionary.languageModel == null) 1 else if (rerank) RERANK_READINGS else MAX_SENTENCES
        val decoded = SentenceDecoder.decodeAll(input, dictionary, taps, before, limit)
        val sentences = if (rerank && decoded.size > 1) reranked(decoded, scorer!!).take(MAX_SENTENCES) else decoded
        val sentence = sentences.firstOrNull() ?: return withInitialWords(input, words, dictionary, before)
        val typed = input.replace(SEPARATOR.toString(), "")
        val corrected = sentence.spelling.replace(" ", "") != typed
        if (sentence.parts.size < 2 && !corrected && dictionary.languageModel == null) {
            return withInitialWords(input, words, dictionary, before)
        }
        return withInitialWords(input, withSentences(sentences.map { wholeInput(it, input.length) }, words, input.length), dictionary, before)
    }

    /** [sentences] reordered with [scorer]'s ln P, or as they are when it gives none. */
    private fun reranked(sentences: List<SentenceDecoder.Sentence>, scorer: SentenceScorer): List<SentenceDecoder.Sentence> {
        val scores = scorer.score(sentences.map { it.text })?.takeIf { it.size == sentences.size } ?: return sentences
        return sentences.indices
            .sortedByDescending { -sentences[it].cost + RERANK_MODEL_WEIGHT * scores[it] + RERANK_CHAR_BONUS * sentences[it].text.length }
            .map { sentences[it] }
    }

    /** [sentence] as a candidate that reads all [length] characters. */
    fun wholeInput(sentence: SentenceDecoder.Sentence, length: Int) =
        Candidate(sentence.text, length, sentence.parts.flatMap { it.syllables }, sentence.parts, sentence.spelling)

    /**
     * [sentences], best first, placed among [words]: the best leads unless the first word is the
     * same reading, and the rest follow the words that read all [length] characters.
     */
    fun withSentences(sentences: List<Candidate>, words: List<Candidate>, length: Int): List<Candidate> {
        val best = sentences.first()
        val top = words.firstOrNull()
        val listed = if (top != null && top.text == best.text && top.consumed >= length) words else listOf(best) + words.filter { it.text != best.text }
        val others = sentences.drop(1).filter { other -> other.parts.orEmpty().size >= 2 && listed.none { it.text == other.text } }
        if (others.isEmpty()) return listed
        val at = listed.indexOfFirst { it.consumed < length }.let { if (it < 0) listed.size else it }
        return listed.subList(0, at) + others + listed.subList(at, listed.size)
    }

    /**
     * [segment] reads "sh" as the start of 上 and "jjfa" as 经济 + 法; here every letter is the first
     * of its own character, so 时候 and 解决方案 are found too. Only words not listed yet are added,
     * likeliest after [before] first: after the words that read all of [input], yet on the first
     * screen unless it is whole pinyin. From three letters on, where few words share the letters,
     * the likeliest goes right behind the top candidate.
     */
    private fun withInitialWords(
        input: String,
        words: List<Candidate>,
        dictionary: PinyinDictionary,
        before: SentenceDecoder.Context?,
    ): List<Candidate> {
        val initials = initialCandidates(input, dictionary, before)
        if (initials.isEmpty()) return words
        return addInitialWords(input, words, initials, isWholePinyin(input, dictionary), dictionary.languageModel != null)
    }

    private fun initialCandidates(input: String, dictionary: PinyinDictionary, before: SentenceDecoder.Context?): List<Candidate> {
        if (input.length !in 2..MAX_INITIALS_LENGTH || SEPARATOR in input) return emptyList()
        val pieces = input.map { PinyinDictionary.Piece(it.toString(), isPrefix = true) }
        val found = dictionary.scoredMatches(pieces, INITIAL_WORDS_LIMIT)
            .sortedBy { SentenceDecoder.wordCostAfter(it, before, dictionary) }
            .map { it.word }
        return found.map { Candidate(it.text, input.length, it.syllables, id = it.id) }
    }

    private fun addInitialWords(input: String, words: List<Candidate>, initialCandidates: List<Candidate>, wholePinyin: Boolean, hasLanguageModel: Boolean): List<Candidate> {
        val added = initialCandidates.filter { candidate -> words.none { it.text == candidate.text } }
        if (added.isEmpty()) return words
        if (wholePinyin) {
            val at = words.indexOfFirst { it.consumed < input.length }.let { if (it < 0) words.size else it }
            return words.subList(0, at) + added + words.subList(at, words.size)
        }
        val lastWord = words.indexOfLast { it.consumed >= input.length && it.text.length > 1 }
        val at = (lastWord + 1).coerceIn(MIN_BEFORE_INITIALS, MAX_BEFORE_INITIALS).coerceAtMost(words.size)
        if (input.length < MIN_LETTERS_NEAR_TOP || at < 1 || !hasLanguageModel) return words.subList(0, at) + added + words.subList(at, words.size)
        return words.subList(0, 1) + added.first() + words.subList(1, at) + added.drop(1) + words.subList(at, words.size)
    }

    /** Other words that read like [part] of a sentence, best first, to swap it for. */
    fun alternatives(part: Candidate, dictionary: PinyinDictionary): List<PinyinDictionary.Word> {
        val pieces = part.syllables.map { PinyinDictionary.Piece(it, isPrefix = false) }
        return dictionary.matches(pieces, ALTERNATIVES_LIMIT).filter { it.text != part.text }
    }

    /** [sentence] with its part at [index] replaced by [word]; picking it learns that word. */
    fun withPart(sentence: Candidate, index: Int, word: PinyinDictionary.Word): Candidate {
        val parts = sentence.parts.orEmpty().toMutableList()
        parts[index] = Candidate(word.text, parts[index].consumed, word.syllables, id = word.id)
        return sentence.copy(
            text = parts.joinToString("") { it.text },
            syllables = parts.flatMap { it.syllables },
            parts = parts,
        )
    }

    /** Whether all of [input] splits into whole syllables, with no initials or half-typed ones. */
    fun isWholePinyin(input: String, dictionary: PinyinDictionary): Boolean {
        val reachable = BooleanArray(input.length + 1)
        reachable[0] = true
        for (start in input.indices) {
            if (!reachable[start]) continue
            for (end in start + 1..minOf(start + MAX_SYLLABLE_LENGTH, input.length)) {
                if (dictionary.isSyllable(input.substring(start, end))) reachable[end] = true
            }
        }
        return reachable[input.length]
    }

    /**
     * yue reads as 月 but also as yu e, 余额; xian also as xi an, 西安. A short input that splits
     * into whole syllables another way gets that reading's best common words a few places down,
     * where they are on the first screen without moving what was first.
     */
    private fun withOtherSplits(input: String, ranked: List<Candidate>, dictionary: PinyinDictionary): List<Candidate> {
        return addOtherSplits(ranked, otherSplitCandidates(input, dictionary))
    }

    private fun otherSplitCandidates(input: String, dictionary: PinyinDictionary): List<Candidate> {
        if (input.length !in 3..MAX_SPLIT_LETTERS || !input.all { it in 'a'..'z' }) return emptyList()
        val usual = segment(input, dictionary).map { it.text }
        val splits = ArrayList<List<String>>()
        val path = ArrayList<String>()
        fun search(position: Int) {
            if (splits.size >= MAX_SPLITS) return
            if (position == input.length) {
                if (path.size >= 2 && path != usual) splits += path.toList()
                return
            }
            for (end in position + 1..minOf(position + MAX_SYLLABLE_LENGTH, input.length)) {
                val piece = input.substring(position, end)
                if (dictionary.isSyllable(piece)) {
                    path += piece
                    search(end)
                    path.removeAt(path.size - 1)
                }
            }
        }
        search(0)
        if (splits.isEmpty()) return emptyList()
        val added = ArrayList<Candidate>()
        for (split in splits) {
            for (match in dictionary.scoredMatches(split.map { PinyinDictionary.Piece(it, isPrefix = false) }, WORDS_PER_SPLIT)) {
                if (match.score >= MIN_SPLIT_WORD_SCORE) {
                    added += Candidate(match.word.text, input.length, match.word.syllables, id = match.word.id)
                }
            }
        }
        return added
    }

    private fun addOtherSplits(ranked: List<Candidate>, others: List<Candidate>): List<Candidate> {
        val seen = ranked.mapTo(HashSet()) { it.text }
        val added = others.filter { seen.add(it.text) }
        if (added.isEmpty()) return ranked
        val at = minOf(OTHER_SPLITS_AT, ranked.size)
        return ranked.subList(0, at) + added + ranked.subList(at, ranked.size)
    }

    private fun wordCandidates(input: String, dictionary: PinyinDictionary): List<Candidate> {
        val syllables = segment(input, dictionary)
        if (syllables.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Candidate>()
        for (count in syllables.size downTo 1) {
            val limit = when {
                count == syllables.size -> FULL_MATCH_LIMIT
                count == 1 -> SINGLE_SYLLABLE_LIMIT
                else -> PARTIAL_MATCH_LIMIT
            }
            val words = dictionary.matches(piecesFor(syllables, count, dictionary), limit)
            // Picking a candidate also eats the separator that follows it.
            var consumed = syllables[count - 1].end
            if (consumed < input.length && input[consumed] == SEPARATOR) consumed++
            for (word in words) {
                if (seen.add(word.text)) result += Candidate(word.text, consumed, word.syllables, id = word.id)
                if (result.size >= MAX_CANDIDATES) return result
            }
        }
        return result
    }
}
