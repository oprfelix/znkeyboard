package dev.zain.znkeyboard.pinyin

/**
 * 9-key (九宫格) pinyin. The composition is the syllables the user locked from the spelling list,
 * then the digits typed, e.g. "ni'4426" or "64426"; a typed separator ("94'26" for xi'an) makes
 * words split there. Candidates use the same [PinyinEngine.Candidate] as 26-key pinyin, so
 * picking, learning and predictions work the same way.
 */
object NineKeyEngine {
    const val SEPARATOR = PinyinEngine.SEPARATOR

    /** [digitStart] is where the digits begin in the composition. */
    private class Parsed(val locked: List<String>, val digits: String, val boundaries: Set<Int>, val digitStart: Int)

    private fun parse(composing: String): Parsed {
        val locked = ArrayList<String>()
        var at = 0
        while (at < composing.length && composing[at].isLetter()) {
            var end = at
            while (end < composing.length && composing[end].isLetter()) end++
            locked += composing.substring(at, end)
            at = if (end < composing.length && composing[end] == SEPARATOR) end + 1 else end
        }
        val digits = StringBuilder()
        val boundaries = HashSet<Int>()
        for (index in at until composing.length) {
            val c = composing[index]
            if (c == SEPARATOR) boundaries += digits.length else digits.append(c)
        }
        return Parsed(locked, digits.toString(), boundaries, at)
    }

    /** The digits read as a whole sentence first (when nothing is locked or separated), then words. */
    fun candidates(composing: String, dictionary: PinyinDictionary): List<PinyinEngine.Candidate> {
        val parsed = parse(composing)
        val words = dictionary.nineKeyMatches(parsed.locked, parsed.digits, parsed.boundaries).map { match ->
            PinyinEngine.Candidate(match.word.text, consumedFor(composing, parsed, match.digits), match.word.syllables)
        }
        if (parsed.locked.isNotEmpty() || parsed.boundaries.isNotEmpty()) return words
        val limit = if (dictionary.languageModel == null) 1 else PinyinEngine.MAX_SENTENCES
        val sentences = SentenceDecoder.decodeAllNineKey(parsed.digits, dictionary, limit)
        if (sentences.isEmpty()) return words
        return PinyinEngine.withSentences(sentences.map { PinyinEngine.wholeInput(it, composing.length) }, words, composing.length)
    }

    /** Characters of [composing] a candidate using [digits] digits takes up, with a following separator. */
    private fun consumedFor(composing: String, parsed: Parsed, digits: Int): Int {
        var at = parsed.digitStart
        var counted = 0
        while (at < composing.length && counted < digits) {
            if (composing[at] != SEPARATOR) counted++
            at++
        }
        if (at < composing.length && composing[at] == SEPARATOR) at++
        return at
    }

    /** What the field shows while typing: pinyin, not digits ("ni'hao" for 64426). */
    fun display(composing: String, dictionary: PinyinDictionary, top: PinyinEngine.Candidate?): String {
        val parsed = parse(composing)
        if (parsed.digits.isEmpty()) return parsed.locked.joinToString(SEPARATOR.toString())
        // The top word's own pinyin when it spells exactly the digits typed; while a syllable is
        // still being typed (6442 → ni'ha), what was typed, not the word's completion (ni'hao).
        val topRest = top?.syllables?.drop(parsed.locked.size)
            ?.takeIf { it.isNotEmpty() && PinyinDictionary.nineKeyCode(it.joinToString("")) == parsed.digits }
        val rest = topRest ?: dictionary.nineKeySpell(parsed.digits).ifEmpty { listOf(parsed.digits) }
        return (parsed.locked + rest).joinToString(SEPARATOR.toString())
    }

    /** The letters to commit when the user ends a composition without picking (Enter). */
    fun raw(composing: String, dictionary: PinyinDictionary, top: PinyinEngine.Candidate?): String =
        display(composing, dictionary, top).replace(SEPARATOR.toString(), "")

    /** Spellings for the first digits not yet locked, for the list beside the keys. */
    fun spellings(composing: String, dictionary: PinyinDictionary): List<String> {
        val parsed = parse(composing)
        val firstGroup = parsed.digits.substring(0, parsed.boundaries.filter { it > 0 }.minOrNull() ?: parsed.digits.length)
        return dictionary.nineKeySpellings(firstGroup)
    }

    /** Locks [spelling] in place of the digits it was typed with. */
    fun lock(composing: String, spelling: String): String {
        val parsed = parse(composing)
        val code = PinyinDictionary.nineKeyCode(spelling)
        if (!parsed.digits.startsWith(code)) return composing
        var at = parsed.digitStart
        var counted = 0
        while (at < composing.length && counted < code.length) {
            if (composing[at] != SEPARATOR) counted++
            at++
        }
        val rest = composing.substring(at).trimStart(SEPARATOR)
        return (parsed.locked + spelling).joinToString(SEPARATOR.toString()) + if (rest.isEmpty()) "" else "$SEPARATOR$rest"
    }

    /** Deletes the last digit; a locked syllable goes back to its digits first. */
    fun backspace(composing: String): String {
        if (composing.isEmpty()) return composing
        val last = composing.last()
        if (!last.isLetter()) return composing.dropLast(1)
        val parsed = parse(composing)
        val unlocked = parsed.locked.dropLast(1)
        val prefix = unlocked.joinToString(SEPARATOR.toString())
        val code = PinyinDictionary.nineKeyCode(parsed.locked.last())
        return if (prefix.isEmpty()) code else "$prefix$SEPARATOR$code"
    }
}
