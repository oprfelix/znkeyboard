package dev.zain.znkeyboard.pinyin

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
    /** [before] is the word committed just before, so the sentence reads on from it. */
    fun candidates(
        input: String,
        dictionary: PinyinDictionary,
        taps: SentenceDecoder.Taps? = null,
        before: SentenceDecoder.Context? = null,
    ): List<Candidate> = withOtherSplits(input, rankedCandidates(input, dictionary, taps, before), dictionary)

    private fun rankedCandidates(
        input: String,
        dictionary: PinyinDictionary,
        taps: SentenceDecoder.Taps?,
        before: SentenceDecoder.Context?,
    ): List<Candidate> {
        val words = wordCandidates(input, dictionary)
        val limit = if (dictionary.languageModel == null) 1 else MAX_SENTENCES
        val sentences = SentenceDecoder.decodeAll(input, dictionary, taps, before, limit)
        val sentence = sentences.firstOrNull() ?: return withInitialWords(input, words, dictionary, before)
        val typed = input.replace(SEPARATOR.toString(), "")
        val corrected = sentence.spelling.replace(" ", "") != typed
        if (sentence.parts.size < 2 && !corrected && dictionary.languageModel == null) {
            return withInitialWords(input, words, dictionary, before)
        }
        return withInitialWords(input, withSentences(sentences.map { wholeInput(it, input.length) }, words, input.length), dictionary, before)
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
        if (input.length !in 2..MAX_INITIALS_LENGTH || SEPARATOR in input) return words
        val pieces = input.map { PinyinDictionary.Piece(it.toString(), isPrefix = true) }
        val found = dictionary.scoredMatches(pieces, INITIAL_WORDS_LIMIT)
            .filter { match -> words.none { it.text == match.word.text } }
            .sortedBy { SentenceDecoder.wordCostAfter(it, before, dictionary) }
            .map { it.word }
        if (found.isEmpty()) return words
        val added = found.map { Candidate(it.text, input.length, it.syllables, id = it.id) }
        if (isWholePinyin(input, dictionary)) {
            val at = words.indexOfFirst { it.consumed < input.length }.let { if (it < 0) words.size else it }
            return words.subList(0, at) + added + words.subList(at, words.size)
        }
        val lastWord = words.indexOfLast { it.consumed >= input.length && it.text.length > 1 }
        val at = (lastWord + 1).coerceIn(MIN_BEFORE_INITIALS, MAX_BEFORE_INITIALS).coerceAtMost(words.size)
        if (input.length < MIN_LETTERS_NEAR_TOP || at < 1 || dictionary.languageModel == null) return words.subList(0, at) + added + words.subList(at, words.size)
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
        if (input.length !in 3..MAX_SPLIT_LETTERS || !input.all { it in 'a'..'z' }) return ranked
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
        if (splits.isEmpty()) return ranked
        val seen = ranked.mapTo(HashSet()) { it.text }
        val added = ArrayList<Candidate>()
        for (split in splits) {
            for (match in dictionary.scoredMatches(split.map { PinyinDictionary.Piece(it, isPrefix = false) }, WORDS_PER_SPLIT)) {
                if (match.score >= MIN_SPLIT_WORD_SCORE && seen.add(match.word.text)) {
                    added += Candidate(match.word.text, input.length, match.word.syllables, id = match.word.id)
                }
            }
        }
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
