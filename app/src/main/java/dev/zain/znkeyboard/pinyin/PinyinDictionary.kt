package dev.zain.znkeyboard.pinyin

import java.io.BufferedReader
import kotlin.math.ln

/**
 * Pinyin → word index loaded from `assets/pinyin/dict.tsv` (`text<TAB>pinyin<TAB>weight`,
 * rows sorted by weight descending). Keys are fuzzy-normalized, so the index must be
 * rebuilt when the fuzzy rules change.
 *
 * Words rank by log weight plus a bonus for how often the user picked them ([learn]); words
 * the user built that the dictionary lacks are added with a modest base. Learning mutates the
 * index, so after construction the dictionary is main-thread only.
 *
 * Each distinct text has an id, its order of first appearance in dict.tsv, which is how
 * [languageModel] names words. Words the user built have none (-1), nor do words from [addWords],
 * which stay out of dict.tsv so the model keeps fitting it.
 */
class PinyinDictionary private constructor(
    val fuzzy: PinyinFuzzy,
    private val rows: List<Row>,
    private val extraRows: MutableList<Row> = ArrayList(),
) {
    class Row(val text: String, val syllables: List<String>, val weight: Long, val id: Int)

    /** One typed piece: a whole syllable that must match exactly, or the start of one. */
    data class Piece(val text: String, val isPrefix: Boolean)

    /** A dictionary word: its text and raw pinyin, one syllable per character; [id] is -1 for built words. */
    class Word(val text: String, val syllables: List<String>, val id: Int = -1, val trade: Boolean = false)

    /**
     * A word found for typed pieces: [score] is what it ranks by (log weight plus learning),
     * [learned] the part of it the user's picks added, [uses] the picks that count toward it
     * (see [learnedUses]).
     */
    class Match(val word: Word, val score: Float, val learned: Float, val uses: Int)

    /** A suggestion to type after a committed word: [suffix] completes it into [word]. */
    class Continuation(val suffix: String, val word: Word)

    /** [rank] is the row's position by weight and breaks score ties. */
    private class Entry(
        val text: String,
        val syllables: Array<String>,
        val raw: List<String>,
        val rank: Int,
        val base: Float,
        val id: Int,
    ) {
        var score = base

        /** On the trade word list, which the sentence decoder favours a little. */
        var trade = false

        /** Picks that count: all of them for a dictionary word, from the second on for a built one. */
        var uses = 0
    }

    /** Normalized syllables joined by spaces → entries, best first. */
    private val exact = HashMap<String, MutableList<Entry>>()

    /** First letter of each raw syllable ("n h" for 你好) → entries; serves 简拼 and half-typed input. */
    private val byInitials = HashMap<String, MutableList<Entry>>()

    /** First character → multi-character entries, best first; serves continuations after a commit. */
    private val byFirstChar = HashMap<Char, MutableList<Entry>>()

    /** Normalized spellings of every syllable the dictionary uses. */
    private val syllables = HashSet<String>()

    /** Every prefix of a syllable, raw and normalized, so half-typed input still segments. */
    private val syllablePrefixes = HashSet<String>()

    /** Entries whose score [learn] changed, so [forgetLearned] can undo it. */
    private val learned = HashSet<Entry>()

    /** Every learned word's [Entry.uses] together: P(word) among the user's picks is uses / this. */
    var learnedUses = 0
        private set

    /** Words the user typed right after a word, with how often ([learnPair]). */
    private val pairs = HashMap<String, HashMap<String, Int>>()
    private val pairTotals = HashMap<String, Int>()

    private val normalizedBySyllable = HashMap<String, String>()

    /** Language-model ids of single characters, for [contextBefore]. */
    private val singleIds by lazy(LazyThreadSafetyMode.NONE) {
        rows.asSequence().filter { it.text.length == 1 }.associate { it.text[0] to it.id }
    }

    /** Keeps [addWords] rows after every dict.tsv row when scores tie. */
    private var extraRank = 0

    /** Changes whenever scores or words change, so cached lookups know to start over. */
    var version = 0
        private set

    /** Every syllable as spelled in the dictionary (zhong, lv…), for typo correction. */
    val rawSyllables = HashSet<String>()

    /** Distinct texts, the ids [languageModel] uses. */
    private val wordCount = (rows.maxOfOrNull { it.id } ?: -1) + 1

    /** ln of all weights together: a word's base less this is its share, ln P. */
    val logTotalWeight = ln(rows.sumOf { it.weight.coerceAtLeast(1L).toDouble() }).toFloat()

    /** Ranks whole sentences by context; only kept when it was built for these rows. */
    var languageModel: PinyinLanguageModel? = null
        set(value) {
            field = value?.takeIf { it.fits(wordCount, rows.size) }
            version++
        }

    init {
        rows.forEachIndexed { rank, row ->
            rawSyllables += row.syllables
            index(newEntry(row.text, row.syllables, rank, ln(row.weight.coerceAtLeast(1L).toFloat()), row.id))
        }
        for (raw in rawSyllables) {
            val normalized = fuzzy.normalize(raw)
            syllables += normalized
            for (end in 1..raw.length) syllablePrefixes += raw.substring(0, end)
            for (end in 1..normalized.length) syllablePrefixes += normalized.substring(0, end)
        }
        indexExtras(extraRows)
    }

    /**
     * The last word of [text] already in the field, longest match first, as context for what is
     * typed next: null when it ends in anything but a dictionary character.
     */
    fun contextBefore(text: CharSequence): SentenceDecoder.Context? {
        for (length in minOf(MAX_CONTEXT_WORD, text.length) downTo 2) {
            val tail = text.subSequence(text.length - length, text.length).toString()
            val entry = byFirstChar[tail[0]]?.firstOrNull { it.text == tail } ?: continue
            return SentenceDecoder.Context(entry.text, entry.id)
        }
        val last = text.lastOrNull() ?: return null
        val id = singleIds[last] ?: return null
        return SentenceDecoder.Context(last.toString(), id)
    }

    /** Reads the trade word list: words already here are marked as trade words, others added. */
    fun addWords(reader: BufferedReader) {
        val rows = ArrayList<Row>()
        reader.forEachLine { line ->
            val columns = line.split('\t')
            if (columns.size == 3) rows += Row(columns[0], columns[1].split(' '), columns[2].toLongOrNull() ?: 1L, -1)
        }
        extraRows += rows
        indexExtras(rows)
        version++
    }

    private fun indexExtras(extras: List<Row>) {
        for (row in extras) {
            if (row.syllables.any { it !in rawSyllables }) continue
            val known = exactMatch(row.text, row.syllables)
            if (known != null) {
                known.trade = true
                continue
            }
            val entry = newEntry(row.text, row.syllables, rows.size + extraRank++, ln(row.weight.coerceAtLeast(1L).toFloat()), -1)
            entry.trade = true
            index(entry)
            reposition(entry)
        }
    }

    private fun exactMatch(text: String, raw: List<String>): Entry? {
        val key = raw.joinToString(" ") { normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
        return exact[key]?.firstOrNull { it.text == text && it.raw == raw }
    }

    // Only a few hundred distinct syllables exist; share one normalized string for each.
    private fun newEntry(text: String, raw: List<String>, rank: Int, base: Float, id: Int): Entry {
        val normalized = raw.map { normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
        return Entry(text, normalized.toTypedArray(), raw, rank, base, id)
    }

    // Rows arrive in weight order, so appending keeps every bucket sorted.
    private fun index(entry: Entry) {
        exact.getOrPut(exactKey(entry)) { mutableListOf() }.add(entry)
        byInitials.getOrPut(initialsKey(entry)) { mutableListOf() }.add(entry)
        if (entry.text.length > 1) byFirstChar.getOrPut(entry.text[0]) { mutableListOf() }.add(entry)
        nineKey?.add(entry)
        initialsPrefixes?.let { set ->
            val key = initialsKey(entry)
            var at = key.indexOf(' ')
            while (at > 0) {
                set += key.substring(0, at)
                at = key.indexOf(' ', at + 1)
            }
            set += key
        }
    }

    private fun exactKey(entry: Entry) = entry.syllables.joinToString(" ")

    private fun initialsKey(entry: Entry) = entry.raw.joinToString(" ") { it.take(1) }

    fun isSyllable(text: String): Boolean = fuzzy.normalize(text) in syllables

    fun isSyllablePrefix(text: String): Boolean = text in syllablePrefixes

    /**
     * Words with one syllable per piece. Exact pieces must equal their syllable; prefix pieces
     * only start it. A single letter is 简拼 for its own initial ("z" covers zh, but "n" never
     * covers l); fuzzy rules apply to longer pieces. When the last piece is a prefix, words
     * spelling it exactly come first.
     */
    fun matches(pieces: List<Piece>, limit: Int): List<Word> = matchEntries(pieces, limit).map(::word)

    /** The best few words for [pieces] with their scores, for sentence search. */
    fun scoredMatches(pieces: List<Piece>, limit: Int): List<Match> =
        matchEntries(pieces, limit).map { Match(word(it), it.score, it.score - it.base, it.uses) }

    private fun matchEntries(pieces: List<Piece>, limit: Int): List<Entry> {
        val normalized = pieces.map { fuzzy.normalize(it.text) }
        if (pieces.none { it.isPrefix }) {
            return exact[normalized.joinToString(" ")].orEmpty().asSequence().take(limit).toList()
        }
        val lastIndex = normalized.lastIndex
        fun fitsPiece(entry: Entry, i: Int): Boolean {
            val piece = pieces[i]
            return when {
                !piece.isPrefix -> entry.syllables[i] == normalized[i]
                piece.text.length == 1 -> entry.raw[i].startsWith(piece.text)
                else -> entry.syllables[i].startsWith(normalized[i]) || entry.raw[i].startsWith(piece.text)
            }
        }
        val candidates = initialsKeys(pieces)
            .flatMap { byInitials[it].orEmpty() }
            .filter { entry -> pieces.indices.all { fitsPiece(entry, it) } }
            .sortedWith(BEST_FIRST)
        val lastExact = candidates.asSequence().filter { it.syllables[lastIndex] == normalized[lastIndex] }
        val lastLonger = candidates.asSequence().filter { it.syllables[lastIndex] != normalized[lastIndex] }
        return (lastExact + lastLonger).take(limit).toList()
    }

    // ---- Sentence search ----

    /** Every leading part of every word's initials key ("y", "y m", "y m y"…), built on first use. */
    private var initialsPrefixes: HashSet<String>? = null

    /**
     * Whether some word's initials start with [initials] (first letters joined by spaces): lets a
     * sentence search stop extending a run of syllables no word could match.
     */
    fun hasInitialsPrefix(initials: String): Boolean {
        val set = initialsPrefixes ?: HashSet<String>().also { set ->
            for (key in byInitials.keys) {
                var at = key.indexOf(' ')
                while (at > 0) {
                    set += key.substring(0, at)
                    at = key.indexOf(' ', at + 1)
                }
                set += key
            }
            initialsPrefixes = set
        }
        return initials in set
    }

    /** A word for 9-key sentence search, and whether the digits were its 简拼. */
    class NineKeyWord(val match: Match, val byInitials: Boolean)

    /** The best [limit] words whose 9-key code is exactly [digits], then the best [limit] whose 简拼 is. */
    fun nineKeyWords(digits: String, limit: Int = 1): List<NineKeyWord> {
        val index = nineKeyIndex()
        fun found(entry: Entry, byInitials: Boolean) = NineKeyWord(Match(word(entry), entry.score, entry.score - entry.base, entry.uses), byInitials)
        val full = index.full[digits].orEmpty().asSequence().take(limit).map { found(it, false) }
        val initials = index.initials[digits].orEmpty().asSequence().filter { it.raw.size == digits.length }.take(limit).map { found(it, true) }
        return (full + initials).toList()
    }

    /** Longer words that start with [text], best first, as the characters still to type. */
    fun continuations(text: String, limit: Int): List<Continuation> {
        if (text.isEmpty()) return emptyList()
        val bucket = byFirstChar[text[0]] ?: return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Continuation>()
        for (entry in bucket) {
            if (entry.text.length <= text.length || !entry.text.startsWith(text)) continue
            val suffix = entry.text.substring(text.length)
            if (seen.add(suffix)) result += Continuation(suffix, word(entry))
            if (result.size >= limit) break
        }
        return result
    }

    /**
     * Applies [uses] picks of [text] spelled [raw]. A word the dictionary lacks is added, so
     * phrases the user built show up for their full pinyin and 简拼 alike.
     */
    fun learn(text: String, raw: List<String>, uses: Int) {
        version++
        if (raw.isEmpty() || raw.size != PinyinUserData.characterCount(text) || raw.any { !isSyllable(it) }) return
        val key = raw.joinToString(" ") { normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
        var entry = exact[key]?.firstOrNull { it.text == text && it.raw == raw }
        if (entry == null) {
            entry = newEntry(text, raw, Int.MAX_VALUE, NEW_WORD_BASE, -1)
            index(entry)
        }
        // A phrase the user built gains from its second pick on: one accidental build (我们 + 一气)
        // must not push a dictionary word with the same pinyin (我们一起) aside.
        val bonus = if (entry.rank == Int.MAX_VALUE) ln(uses.toFloat().coerceAtLeast(1f)) else ln(1f + uses)
        entry.score = entry.base + LEARN_BONUS * bonus
        val counted = if (entry.rank == Int.MAX_VALUE) (uses - 1).coerceAtLeast(0) else uses
        learnedUses += counted - entry.uses
        entry.uses = counted
        learned += entry
        reposition(entry)
    }

    /** Applies [uses] times that the user typed [next] right after [previous]. */
    fun learnPair(previous: String, next: String, uses: Int) {
        if (previous.isEmpty() || next.isEmpty()) return
        version++
        val old = pairs.getOrPut(previous) { HashMap(4) }.put(next, uses) ?: 0
        pairTotals[previous] = (pairTotals[previous] ?: 0) + uses - old
    }

    /** How often the user typed [next] right after [previous]. */
    fun pairUses(previous: String, next: String): Int = pairs[previous]?.get(next) ?: 0

    /** How often the user typed anything after [previous] (that was learned as a pair). */
    fun pairTotal(previous: String): Int = pairTotals[previous] ?: 0

    private fun forgetPairs(text: String) {
        pairs.remove(text)
        pairTotals.remove(text)
        for ((previous, next) in pairs) {
            val uses = next.remove(text) ?: continue
            pairTotals[previous] = (pairTotals[previous] ?: 0) - uses
        }
    }

    /** Undoes [learn] for one word: a dictionary word goes back to its own rank, a built one goes. */
    fun forget(text: String) {
        version++
        forgetPairs(text)
        for (entry in learned.filter { it.text == text }) {
            learned -= entry
            learnedUses -= entry.uses
            entry.uses = 0
            if (entry.rank == Int.MAX_VALUE) {
                exact[exactKey(entry)]?.remove(entry)
                byInitials[initialsKey(entry)]?.remove(entry)
                if (entry.text.length > 1) byFirstChar[entry.text[0]]?.remove(entry)
                nineKey?.remove(entry)
            } else {
                entry.score = entry.base
                reposition(entry)
            }
        }
    }

    /** Whether [text] is ranked by the user's picks, so there is something to forget. */
    fun isLearned(text: String): Boolean = learned.any { it.text == text }

    /** Drops everything [learn] did. */
    fun forgetLearned() {
        version++
        pairs.clear()
        pairTotals.clear()
        learnedUses = 0
        for (entry in learned) {
            entry.uses = 0
            if (entry.rank == Int.MAX_VALUE) {
                exact[exactKey(entry)]?.remove(entry)
                byInitials[initialsKey(entry)]?.remove(entry)
                if (entry.text.length > 1) byFirstChar[entry.text[0]]?.remove(entry)
                nineKey?.remove(entry)
            } else {
                entry.score = entry.base
                reposition(entry)
            }
        }
        learned.clear()
    }

    private fun reposition(entry: Entry) {
        resort(exact[exactKey(entry)], entry)
        resort(byInitials[initialsKey(entry)], entry)
        if (entry.text.length > 1) resort(byFirstChar[entry.text[0]], entry)
        nineKey?.let { index ->
            resort(index.full[index.fullKey(entry)], entry)
            resort(index.initials[index.initialsKey(entry)], entry)
        }
    }

    // ---- 9-key (九宫格) ----

    /** Built on first use, so 26-key typing pays nothing for it. */
    private var nineKey: NineKeyIndex? = null

    /** Words found for 9-key digits: [digits] is how many of the unlocked digits it uses. */
    class NineKeyMatch(val word: Word, val digits: Int)

    /** Digit codes of words, of their initials (简拼) and of every syllable. */
    private inner class NineKeyIndex {
        val full = HashMap<String, MutableList<Entry>>()
        val initials = HashMap<String, MutableList<Entry>>()
        /** Code → syllables spelled with it, most used first. */
        val syllablesByCode = HashMap<String, List<String>>()
        /** Syllable → its most used single character, for ranking spellings and stroke input. */
        val topChar = HashMap<String, Entry>()

        fun fullKey(entry: Entry) = entry.raw.joinToString("") { nineKeyCode(it) }
        fun initialsKey(entry: Entry) = entry.raw.joinToString("") { nineKeyCode(it.take(1)) }

        fun add(entry: Entry) {
            full.getOrPut(fullKey(entry)) { mutableListOf() }.add(entry)
            if (entry.raw.size >= 2) initials.getOrPut(initialsKey(entry)) { mutableListOf() }.add(entry)
        }

        fun remove(entry: Entry) {
            full[fullKey(entry)]?.remove(entry)
            initials[initialsKey(entry)]?.remove(entry)
        }

        init {
            val singles = HashMap<String, MutableList<Entry>>()
            for (bucket in exact.values) {
                for (entry in bucket) {
                    add(entry)
                    if (entry.raw.size == 1) singles.getOrPut(entry.raw[0]) { mutableListOf() }.add(entry)
                }
            }
            for (bucket in full.values) bucket.sortWith(BEST_FIRST)
            for (bucket in initials.values) bucket.sortWith(BEST_FIRST)
            val usage = singles.mapValues { (_, entries) -> entries.minWith(BEST_FIRST) }
            topChar += usage
            syllablesByCode += usage.keys.groupBy { nineKeyCode(it) }
                .mapValues { (_, spellings) -> spellings.sortedWith(compareBy(BEST_FIRST) { usage.getValue(it) }) }
        }
    }

    private fun nineKeyIndex(): NineKeyIndex = nineKey ?: NineKeyIndex().also { nineKey = it }

    /**
     * Words for 9-key input: [locked] syllables the user picked from the spelling list, then
     * [digits] (2–9). [boundaries] are digit offsets where the user typed a separator, which a
     * word must split at. Best first: words using every digit, the last syllable possibly still
     * being typed, then 简拼, then words using a leading part of the digits.
     */
    fun nineKeyMatches(locked: List<String>, digits: String, boundaries: Set<Int>): List<NineKeyMatch> {
        val index = nineKeyIndex()
        val lockedCode = locked.joinToString("") { nineKeyCode(it) }
        val lockedNormalized = locked.map { fuzzy.normalize(it) }
        val seen = HashSet<String>()
        val result = ArrayList<NineKeyMatch>()

        fun fits(entry: Entry, used: Int): Boolean {
            if (entry.raw.size <= locked.size && used > 0) return false
            if (lockedNormalized.indices.any { entry.syllables.getOrNull(it) != lockedNormalized[it] }) return false
            // Separators the user typed must fall between this word's syllables.
            if (boundaries.isNotEmpty()) {
                val ends = HashSet<Int>()
                var at = -lockedCode.length
                for (syllable in entry.raw) {
                    at += syllable.length
                    if (at > 0) ends += at
                }
                if (boundaries.any { it in 1 until used && it !in ends }) return false
            }
            return true
        }

        fun take(entries: Sequence<Entry>, used: Int, limit: Int) {
            var added = 0
            for (entry in entries) {
                if (added >= limit) break
                if (seen.add(entry.text)) {
                    result += NineKeyMatch(word(entry), used)
                    added++
                }
            }
        }

        // Every digit used. When that only spells single characters (64 → 你, 米…), 简拼 words
        // (64 → 你好) come after the first few rather than after all of them.
        val whole = index.full[lockedCode + digits].orEmpty().filter { fits(it, digits.length) }
        val singlesOnly = locked.isEmpty() && whole.isNotEmpty() && whole.all { it.raw.size == 1 }
        if (singlesOnly && digits.length >= 2) {
            take(whole.asSequence(), digits.length, SINGLES_BEFORE_INITIALS)
            take(index.initials[digits].orEmpty().asSequence().filter { it.raw.size == digits.length }, digits.length, INITIALS_LIMIT)
        }
        take(whole.asSequence(), digits.length, FULL_LIMIT)
        // The last syllable still being typed: complete it from every syllable code it starts.
        val growing = ArrayList<Entry>()
        for (split in digits.length - 1 downTo maxOf(0, digits.length - MAX_CODE_LENGTH + 1)) {
            val tail = digits.substring(split)
            val head = lockedCode + digits.substring(0, split)
            for (code in index.syllablesByCode.keys) {
                if (code.length <= tail.length || !code.startsWith(tail)) continue
                index.full[head + code]?.forEach { entry ->
                    if (nineKeyCode(entry.raw.last()) == code && fits(entry, digits.length)) growing += entry
                }
            }
        }
        take(growing.sortedWith(BEST_FIRST).asSequence(), digits.length, FULL_LIMIT)
        // 简拼: one digit per character.
        if (locked.isEmpty() && digits.length >= 2) {
            take(index.initials[digits].orEmpty().asSequence().filter { it.raw.size == digits.length }, digits.length, INITIALS_LIMIT)
        }
        // A leading part of the digits; the rest stays for the next pick.
        for (used in digits.length - 1 downTo 1) {
            val entries = index.full[lockedCode + digits.substring(0, used)].orEmpty().asSequence().filter { fits(it, used) }
            take(entries, used, if (used <= MAX_CODE_LENGTH) SINGLE_LIMIT else PARTIAL_LIMIT)
        }
        // Everything locked and nothing typed after it.
        if (digits.isEmpty() && locked.isNotEmpty()) {
            take(index.full[lockedCode].orEmpty().asSequence().filter { it.raw.size == locked.size && fits(it, 0) }, 0, FULL_LIMIT)
        }
        return result
    }

    /** Syllables a run of digits can start with, longest and most used first, then its letters. */
    fun nineKeySpellings(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()
        val index = nineKeyIndex()
        val result = ArrayList<String>()
        for (length in minOf(MAX_CODE_LENGTH, digits.length) downTo 1) {
            result += index.syllablesByCode[digits.substring(0, length)].orEmpty()
        }
        NINE_KEY_LETTERS[digits[0]]?.forEach { letter -> if ("$letter" !in result) result += "$letter" }
        return result
    }

    /** The likeliest pinyin for [digits], for showing what is being typed (ni'hao for 64426). */
    fun nineKeySpell(digits: String): List<String> {
        val result = ArrayList<String>()
        var at = 0
        while (at < digits.length) {
            val spelling = nineKeySpellings(digits.substring(at)).firstOrNull() ?: break
            result += spelling
            at += spelling.length
        }
        return result
    }

    /** A character's score (with what the user picked) and pinyin, for ranking stroke input. */
    fun characterInfo(char: String): Pair<Float, List<String>>? {
        val entry = characters()[char] ?: return null
        return entry.score to entry.raw
    }

    private var characterIndex: HashMap<String, Entry>? = null

    private fun characters(): HashMap<String, Entry> = characterIndex ?: HashMap<String, Entry>().also { map ->
        for (bucket in exact.values) for (entry in bucket) {
            if (entry.text.length == 1) {
                val current = map[entry.text]
                if (current == null || BEST_FIRST.compare(entry, current) < 0) map[entry.text] = entry
            }
        }
        characterIndex = map
    }

    /** Moves [entry] to its place in the otherwise sorted [bucket]. */
    private fun resort(bucket: MutableList<Entry>?, entry: Entry) {
        if (bucket == null || !bucket.remove(entry)) return
        var low = 0
        var high = bucket.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (BEST_FIRST.compare(bucket[mid], entry) <= 0) low = mid + 1 else high = mid
        }
        bucket.add(low, entry)
    }

    private fun word(entry: Entry) = Word(entry.text, entry.raw, entry.id, entry.trade)

    /** Raw initials each piece may start with; only n = l can change a first letter. */
    private fun initialsKeys(pieces: List<Piece>): List<String> {
        var keys = listOf("")
        pieces.forEachIndexed { i, piece ->
            val first = piece.text.first()
            val options = if (piece.text.length > 1 && PinyinFuzzyRule.NL in fuzzy.rules && (first == 'n' || first == 'l')) {
                listOf('n', 'l')
            } else {
                listOf(first)
            }
            keys = keys.flatMap { key -> options.map { if (i == 0) "$it" else "$key $it" } }
        }
        return keys
    }

    /** A fresh index under [rules], without anything learned; the caller re-applies its words. */
    fun withFuzzy(rules: Set<PinyinFuzzyRule>): PinyinDictionary =
        PinyinDictionary(PinyinFuzzy(rules), rows, extraRows).also { it.languageModel = languageModel }

    companion object {
        /** About the weight of an ordinary two-character word. */
        private val NEW_WORD_BASE = ln(20_000f)

        /** One pick multiplies a word's weight by ~16, three by ~256. */
        private const val LEARN_BONUS = 4f

        private val BEST_FIRST = compareByDescending<Entry> { it.score }.thenBy { it.rank }

        private const val MAX_CONTEXT_WORD = 6
        private const val FULL_LIMIT = 30
        private const val INITIALS_LIMIT = 10
        private const val SINGLES_BEFORE_INITIALS = 6
        private const val PARTIAL_LIMIT = 8
        private const val SINGLE_LIMIT = 40
        private const val MAX_CODE_LENGTH = 6

        /** Phone keypad letters; ü is typed as v, on 8. */
        private val NINE_KEY_LETTERS = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
        )
        private val NINE_KEY_DIGIT = NINE_KEY_LETTERS.flatMap { (digit, letters) -> letters.map { it to digit } }.toMap()

        /** "nihao" → "64426". */
        fun nineKeyCode(text: String): String = buildString(text.length) { for (c in text) append(NINE_KEY_DIGIT[c] ?: c) }

        fun load(reader: BufferedReader, rules: Set<PinyinFuzzyRule>): PinyinDictionary {
            val rows = ArrayList<Row>(200_000)
            // Rows repeat the same few hundred syllables; keep one copy of each string.
            val syllableCache = HashMap<String, String>()
            val ids = HashMap<String, Int>(200_000)
            reader.forEachLine { line ->
                val firstTab = line.indexOf('\t')
                val secondTab = line.indexOf('\t', firstTab + 1)
                if (firstTab > 0 && secondTab > firstTab) {
                    val syllables = line.substring(firstTab + 1, secondTab).split(' ')
                        .map { syllableCache.getOrPut(it) { it } }
                    val weight = line.substring(secondTab + 1).toLongOrNull() ?: 1L
                    val text = line.substring(0, firstTab)
                    rows += Row(text, syllables, weight, ids.getOrPut(text) { ids.size })
                }
            }
            return PinyinDictionary(PinyinFuzzy(rules), rows)
        }
    }
}
