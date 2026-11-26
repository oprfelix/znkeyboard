package dev.zain.znkeyboard.pinyin

import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import kotlin.math.ln

/**
 * Pinyin → word index loaded from `assets/pinyin/dict.tsv` (`text<TAB>pinyin<TAB>weight`,
 * rows sorted by weight descending). Keys are fuzzy-normalized, so the index must be
 * rebuilt when the fuzzy rules change.
 *
 * Words rank by log weight plus a bonus for how often the user picked them ([learn]); words
 * the user built that the dictionary lacks are added with a modest base. Learning lives in a
 * versioned overlay, so a sealed base may be shared by read-only views.
 *
 * Each distinct text has an id, its order of first appearance in dict.tsv, which is how
 * [languageModel] names words. Words the user built have none (-1), nor do words from [addWords],
 * which stay out of dict.tsv so the model keeps fitting it.
 */
class PinyinDictionary private constructor(
    val fuzzy: PinyinFuzzy,
    private val rows: List<Row>,
    private val extraRows: MutableList<Row> = ArrayList(),
) : Cloneable {
    private val baseSealed = AtomicBoolean(false)
    private var readOnly = false

    /** Shares the sealed base indexes while retaining this exact learning version. */
    @Synchronized fun snapshotView(): PinyinDictionary {
        baseSealed.set(true)
        return (super.clone() as PinyinDictionary).also { it.readOnly = true }
    }

    private fun requireWritable() { check(!readOnly) { "A dictionary snapshot is read-only" } }
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
        /** On the trade word list, which the sentence decoder favours a little. */
        var trade = false
    }

    /** Shared by shallow dictionary views; each cache is initialized once under this monitor. */
    private class SharedCaches {
        @Volatile var initialsPrefixes: HashSet<String>? = null
        @Volatile var nineKey: NineKeyIndex? = null
        @Volatile var characters: HashMap<String, List<Entry>>? = null
    }
    private val sharedCaches = SharedCaches()

    private data class LearnedValue(val score: Float, val uses: Int)

    /** Replaced only when learning changes; old readers retain their exact version. */
    private data class LearningState(
        val version: Int,
        val values: Map<Entry, LearnedValue>,
        val additions: Set<Entry>,
        val addedExact: Map<String, List<Entry>>,
        val addedInitials: Map<String, List<Entry>>,
        val addedFirstChar: Map<Char, List<Entry>>,
        val addedNineFull: Map<String, List<Entry>>,
        val addedNineInitials: Map<String, List<Entry>>,
        val pairs: Map<String, Map<String, Int>>,
        val pairTotals: Map<String, Int>,
        val uses: Int,
    )

    // Mutations use this dictionary's monitor; a snapshot keeps its own stable reference.
    @Volatile private var learning = LearningState(0, emptyMap(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0)

    private fun score(entry: Entry): Float {
        val values = learning.values
        return if (values.isEmpty()) entry.base else values[entry]?.score ?: entry.base
    }
    private fun uses(entry: Entry): Int {
        val values = learning.values
        return if (values.isEmpty()) 0 else values[entry]?.uses ?: 0
    }
    private val bestFirst: Comparator<Entry> get() = compareByDescending<Entry> { score(it) }.thenBy { it.rank }
    private fun ranked(entries: List<Entry>): List<Entry> {
        if (entries.size < 2) return entries
        val values = learning.values
        return if (values.isEmpty() || entries.none { it in values }) entries else entries.sortedWith(bestFirst)
    }
    private fun <K> combined(base: Map<K, out List<Entry>>, added: Map<K, List<Entry>>, key: K): List<Entry> {
        val original = base[key].orEmpty()
        val extra = added[key].orEmpty()
        return if (extra.isEmpty()) original else if (original.isEmpty()) extra else original + extra
    }
    private fun exactBucket(key: String): List<Entry> = if (learning.additions.isEmpty()) exact[key].orEmpty() else combined(exact, learning.addedExact, key)
    private fun initialsBucket(key: String): List<Entry> = if (learning.additions.isEmpty()) byInitials[key].orEmpty() else combined(byInitials, learning.addedInitials, key)
    private fun firstCharBucket(key: Char): List<Entry> = if (learning.additions.isEmpty()) byFirstChar[key].orEmpty() else combined(byFirstChar, learning.addedFirstChar, key)
    private fun nineFullBucket(key: String): List<Entry> = if (learning.additions.isEmpty()) nineKeyIndex().full[key].orEmpty() else combined(nineKeyIndex().full, learning.addedNineFull, key)
    private fun nineInitialsBucket(key: String): List<Entry> = if (learning.additions.isEmpty()) nineKeyIndex().initials[key].orEmpty() else combined(nineKeyIndex().initials, learning.addedNineInitials, key)
    private fun <K> withoutEntry(source: Map<K, List<Entry>>, key: K, entry: Entry): Map<K, List<Entry>> {
        val remaining = source[key].orEmpty().filterNot { it === entry }
        return if (remaining.isEmpty()) source - key else source + (key to remaining)
    }

    /** Normalized syllables joined by spaces → base entries in original weight order. */
    private val exact = HashMap<String, MutableList<Entry>>()

    /** First letter of each raw syllable ("n h" for 你好) → entries; serves 简拼 and half-typed input. */
    private val byInitials = HashMap<String, MutableList<Entry>>()

    /** First character → multi-character entries, best first; serves continuations after a commit. */
    private val byFirstChar = HashMap<Char, MutableList<Entry>>()

    /** Normalized spellings of every syllable the dictionary uses. */
    private val syllables = HashSet<String>()

    /** Every prefix of a syllable, raw and normalized, so half-typed input still segments. */
    private val syllablePrefixes = HashSet<String>()

    /** Every learned word's picks counted by the sentence scorer. */
    val learnedUses: Int get() = learning.uses

    private val normalizedBySyllable = HashMap<String, String>()

    /** Language-model ids of single characters, for [contextBefore]. */
    private val singleIds by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        rows.asSequence().filter { it.text.length == 1 }.associate { it.text[0] to it.id }
    }

    /** Keeps [addWords] rows after every dict.tsv row when scores tie. */
    private var extraRank = 0

    /** Changes whenever scores or words change, so cached lookups know to start over. */
    var version = 0
        private set

    /** Every syllable as spelled in the dictionary (zhong, lv…), for typo correction. */
    private val rawSyllableStorage = HashSet<String>()
    val rawSyllables: Set<String> = Collections.unmodifiableSet(rawSyllableStorage)

    /** Distinct texts, the ids [languageModel] uses. */
    private val wordCount = (rows.maxOfOrNull { it.id } ?: -1) + 1

    /** ln of all weights together: a word's base less this is its share, ln P. */
    val logTotalWeight = ln(rows.sumOf { it.weight.coerceAtLeast(1L).toDouble() }).toFloat()

    /** Ranks whole sentences by context; only kept when it was built for these rows. */
    @set:Synchronized var languageModel: PinyinLanguageModel? = null
        set(value) {
            requireWritable()
            field = value?.takeIf { it.fits(wordCount, rows.size) }
            version++
        }

    init {
        rows.forEachIndexed { rank, row ->
            rawSyllableStorage += row.syllables
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
            val entry = ranked(firstCharBucket(tail[0])).firstOrNull { it.text == tail } ?: continue
            return SentenceDecoder.Context(entry.text, entry.id)
        }
        val last = text.lastOrNull() ?: return null
        val id = singleIds[last] ?: return null
        return SentenceDecoder.Context(last.toString(), id)
    }

    /** Reads the trade word list: words already here are marked as trade words, others added. */
    @Synchronized fun addWords(reader: BufferedReader) {
        requireWritable()
        check(!baseSealed.get()) { "Base dictionary is sealed after a snapshot is published" }
        val rows = ArrayList<Row>()
        reader.forEachLine { line ->
            val columns = line.split('\t')
            if (columns.size == 3) rows += Row(columns[0], Collections.unmodifiableList(ArrayList(columns[1].split(' '))), columns[2].toLongOrNull() ?: 1L, -1)
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
        val normalized = raw.map { if (baseSealed.get()) fuzzy.normalize(it) else normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
        return Entry(text, normalized.toTypedArray(), Collections.unmodifiableList(ArrayList(raw)), rank, base, id)
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
        matchEntries(pieces, limit).map { Match(word(it), score(it), score(it) - it.base, uses(it)) }

    private fun matchEntries(pieces: List<Piece>, limit: Int): List<Entry> {
        val normalized = pieces.map { fuzzy.normalize(it.text) }
        if (pieces.none { it.isPrefix }) {
            val bucket = exactBucket(normalized.joinToString(" "))
            return typedFirst(ranked(bucket), pieces).take(limit).toList()
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
            .flatMap { initialsBucket(it) }
            .filter { entry -> pieces.indices.all { fitsPiece(entry, it) } }
            .sortedWith(bestFirst)
            .let { typedFirst(it, pieces).toList() }
        val lastExact = candidates.asSequence().filter { it.syllables[lastIndex] == normalized[lastIndex] }
        val lastLonger = candidates.asSequence().filter { it.syllables[lastIndex] != normalized[lastIndex] }
        return (lastExact + lastLonger).take(limit).toList()
    }

    /**
     * [entries] (best first) with what was typed first: a word read only through a fuzzy rule
     * (li for ni) counts as much less used, per syllable, so it follows the words spelled as
     * typed unless far more common. A whole syllable typed last is what was typed, though it is
     * still a prefix: xing words come after xin ones.
     */
    private fun typedFirst(entries: List<Entry>, pieces: List<Piece>): Sequence<Entry> {
        if (fuzzy.rules.isEmpty() || entries.size < 2) return entries.asSequence()
        val whole = pieces.map { !it.isPrefix || it.text in rawSyllables }
        fun fuzzyCount(entry: Entry): Int = pieces.indices.count { i ->
            val raw = entry.raw.getOrNull(i) ?: return@count false
            if (whole[i]) raw != pieces[i].text else !raw.startsWith(pieces[i].text)
        }
        if (entries.none { fuzzyCount(it) > 0 }) return entries.asSequence()
        return entries.sortedWith(compareByDescending<Entry> { score(it) - FUZZY_PENALTY * fuzzyCount(it) }.thenBy { it.rank }).asSequence()
    }

    // ---- Sentence search ----

    /** Every leading part of every word's initials key ("y", "y m", "y m y"…), built on first use. */
    private val initialsPrefixes: HashSet<String>? get() = sharedCaches.initialsPrefixes

    /**
     * Whether some word's initials start with [initials] (first letters joined by spaces): lets a
     * sentence search stop extending a run of syllables no word could match.
     */
    fun hasInitialsPrefix(initials: String): Boolean {
        val set = initialsPrefixes ?: synchronized(sharedCaches) {
            sharedCaches.initialsPrefixes ?: HashSet<String>().also { built ->
                for (key in byInitials.keys) {
                    var at = key.indexOf(' ')
                    while (at > 0) {
                        built += key.substring(0, at)
                        at = key.indexOf(' ', at + 1)
                    }
                    built += key
                }
                sharedCaches.initialsPrefixes = built
            }
        }
        return initials in set || learning.addedInitials.keys.any { it == initials || it.startsWith("$initials ") }
    }

    /** A word for 9-key sentence search, and whether the digits were its 简拼. */
    class NineKeyWord(val match: Match, val byInitials: Boolean)

    /** The best [limit] words whose 9-key code is exactly [digits], then the best [limit] whose 简拼 is. */
    fun nineKeyWords(digits: String, limit: Int = 1): List<NineKeyWord> {
        val index = nineKeyIndex()
        fun found(entry: Entry, byInitials: Boolean) = NineKeyWord(Match(word(entry), score(entry), score(entry) - entry.base, uses(entry)), byInitials)
        val full = ranked(nineFullBucket(digits)).asSequence().take(limit).map { found(it, false) }
        val initials = ranked(nineInitialsBucket(digits)).asSequence().filter { it.raw.size == digits.length }.take(limit).map { found(it, true) }
        return (full + initials).toList()
    }

    /** Each word id's best row (rows come by weight, most used first), to read a predicted word. */
    private val rowById: IntArray by lazy {
        IntArray(wordCount) { -1 }.also { byId ->
            rows.forEachIndexed { index, row -> if (row.id in byId.indices && byId[row.id] < 0) byId[row.id] = index }
        }
    }

    /**
     * Words likely to come next after [text] (its last word, by the language model): 发货 → 时间,
     * 好的 → 朋友. Only words of two or more Chinese characters in common use: the model was
     * learned from the web, and its single characters and rare words (多少 → 马赫) are noise.
     */
    fun nextWords(text: String, id: Int, limit: Int): List<Word> {
        val model = languageModel ?: return emptyList()
        val previous = if (id >= 0) id else contextBefore(text)?.id ?: return emptyList()
        if (previous < 0) return emptyList()
        val result = ArrayList<Word>()
        val seen = HashSet<String>()
        for ((next, logP) in model.followers(previous, limit * 4)) {
            if (logP < MIN_NEXT_WORD_LOG_P) break
            val row = rowById.getOrNull(next)?.takeIf { it >= 0 }?.let { rows[it] } ?: continue
            if (model.unigram(next) < MIN_NEXT_WORD_UNIGRAM || row.text.length < 2) continue
            if (!row.text.all { it in '\u4e00'..'\u9fff' } || !seen.add(row.text)) continue
            result += Word(row.text, row.syllables, row.id)
            if (result.size >= limit) break
        }
        return result
    }

    /** Longer words that start with [text], best first, as the characters still to type. */
    fun continuations(text: String, limit: Int): List<Continuation> {
        if (text.isEmpty()) return emptyList()
        val bucket = firstCharBucket(text[0])
        if (bucket.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<Continuation>()
        for (entry in ranked(bucket)) {
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
    @Synchronized fun learn(text: String, raw: List<String>, uses: Int) {
        requireWritable()
        version++
        if (raw.isEmpty() || raw.size != PinyinUserData.characterCount(text) || raw.any { !isSyllable(it) }) return
        val key = raw.joinToString(" ") { if (baseSealed.get()) fuzzy.normalize(it) else normalizedBySyllable.getOrPut(it) { fuzzy.normalize(it) } }
        var entry = exactBucket(key).firstOrNull { it.text == text && it.raw == raw }
        val old = learning
        if (entry == null) {
            entry = newEntry(text, raw, Int.MAX_VALUE, NEW_WORD_BASE, -1)
        }
        // A phrase the user built gains from its second pick on: one accidental build (我们 + 一气)
        // must not push a dictionary word with the same pinyin (我们一起) aside.
        val bonus = if (entry.rank == Int.MAX_VALUE) ln(uses.toFloat().coerceAtLeast(1f)) else ln(1f + uses)
        val newScore = entry.base + LEARN_BONUS * bonus
        val counted = if (entry.rank == Int.MAX_VALUE) (uses - 1).coerceAtLeast(0) else uses
        val added = entry.rank == Int.MAX_VALUE && entry !in old.additions
        learning = old.copy(
            version = version,
            values = old.values + (entry to LearnedValue(newScore, counted)),
            additions = if (entry.rank == Int.MAX_VALUE) old.additions + entry else old.additions,
            addedExact = if (added) old.addedExact + (key to (old.addedExact[key].orEmpty() + entry)) else old.addedExact,
            addedInitials = if (added) old.addedInitials + (initialsKey(entry) to (old.addedInitials[initialsKey(entry)].orEmpty() + entry)) else old.addedInitials,
            addedFirstChar = if (added && entry.text.length > 1) old.addedFirstChar + (entry.text[0] to (old.addedFirstChar[entry.text[0]].orEmpty() + entry)) else old.addedFirstChar,
            addedNineFull = if (added) old.addedNineFull + (nineKeyCode(entry.raw.joinToString("")) to (old.addedNineFull[nineKeyCode(entry.raw.joinToString(""))].orEmpty() + entry)) else old.addedNineFull,
            addedNineInitials = if (added && entry.raw.size >= 2) old.addedNineInitials + (nineKeyCode(entry.raw.joinToString("") { it.take(1) }) to (old.addedNineInitials[nineKeyCode(entry.raw.joinToString("") { it.take(1) })].orEmpty() + entry)) else old.addedNineInitials,
            uses = old.uses + counted - (old.values[entry]?.uses ?: 0),
        )
    }

    /** Applies [uses] times that the user typed [next] right after [previous]. */
    @Synchronized fun learnPair(previous: String, next: String, uses: Int) {
        requireWritable()
        if (previous.isEmpty() || next.isEmpty()) return
        version++
        val old = learning
        val previousPairs = old.pairs[previous].orEmpty()
        val previousUses = previousPairs[next] ?: 0
        learning = old.copy(
            version = version,
            pairs = old.pairs + (previous to (previousPairs + (next to uses))),
            pairTotals = old.pairTotals + (previous to ((old.pairTotals[previous] ?: 0) + uses - previousUses)),
        )
    }

    /** How often the user typed [next] right after [previous]. */
    fun pairUses(previous: String, next: String): Int = learning.pairs[previous]?.get(next) ?: 0

    /** How often the user typed anything after [previous] (that was learned as a pair). */
    fun pairTotal(previous: String): Int = learning.pairTotals[previous] ?: 0

    private fun forgetPairs(text: String) {
        val old = learning
        val pairs = old.pairs.filterKeys { it != text }.mapValues { (_, next) -> next.filterKeys { it != text } }
        learning = old.copy(version = version, pairs = pairs, pairTotals = pairs.mapValues { (_, next) -> next.values.sum() })
    }

    /** Undoes [learn] for one word: a dictionary word goes back to its own rank, a built one goes. */
    @Synchronized fun forget(text: String) {
        requireWritable()
        version++
        forgetPairs(text)
        for (entry in learning.values.keys.filter { it.text == text }) removeLearnedEntry(entry)
    }

    /** Keeps pair learning while removing a word evicted by the bounded user-data table. */
    @Synchronized fun discardPrunedWord(text: String, raw: List<String>) {
        requireWritable()
        val entry = learning.values.keys.firstOrNull { it.text == text && it.raw == raw } ?: return
        version++
        removeLearnedEntry(entry)
    }

    private fun removeLearnedEntry(entry: Entry) {
        val old = learning
        learning = old.copy(
                version = version,
                values = old.values - entry,
                additions = old.additions - entry,
                addedExact = withoutEntry(old.addedExact, exactKey(entry), entry),
                addedInitials = withoutEntry(old.addedInitials, initialsKey(entry), entry),
                addedFirstChar = if (entry.text.length > 1) withoutEntry(old.addedFirstChar, entry.text[0], entry) else old.addedFirstChar,
                addedNineFull = withoutEntry(old.addedNineFull, nineKeyCode(entry.raw.joinToString("")), entry),
                addedNineInitials = withoutEntry(old.addedNineInitials, nineKeyCode(entry.raw.joinToString("") { it.take(1) }), entry),
                uses = old.uses - (old.values[entry]?.uses ?: 0),
        )
    }

    /** Whether [text] is ranked by the user's picks, so there is something to forget. */
    fun isLearned(text: String): Boolean = learning.values.keys.any { it.text == text }

    /** Drops everything [learn] did. */
    @Synchronized fun forgetLearned() {
        requireWritable()
        version++
        learning = LearningState(version, emptyMap(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0)
    }

    private fun reposition(entry: Entry) {
        // Trade rows are installed before a base is sealed; learning never reaches this path.
        exact[exactKey(entry)]?.sortWith(BASE_FIRST)
        byInitials[initialsKey(entry)]?.sortWith(BASE_FIRST)
        if (entry.text.length > 1) byFirstChar[entry.text[0]]?.sortWith(BASE_FIRST)
        nineKey?.let { index ->
            index.full[index.fullKey(entry)]?.sortWith(BASE_FIRST)
            index.initials[index.initialsKey(entry)]?.sortWith(BASE_FIRST)
        }
    }

    // ---- 9-key (九宫格) ----

    /** Built on first use, so 26-key typing pays nothing for it. */
    private val nineKey: NineKeyIndex? get() = sharedCaches.nineKey

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
            for (bucket in full.values) bucket.sortWith(BASE_FIRST)
            for (bucket in initials.values) bucket.sortWith(BASE_FIRST)
            val usage = singles.mapValues { (_, entries) -> entries.minWith(BASE_FIRST) }
            topChar += usage
            syllablesByCode += usage.keys.groupBy { nineKeyCode(it) }
                .mapValues { (_, spellings) -> spellings.sortedWith(compareBy(BASE_FIRST) { usage.getValue(it) }) }
        }
    }

    private fun nineKeyIndex(): NineKeyIndex = nineKey ?: synchronized(sharedCaches) {
        sharedCaches.nineKey ?: NineKeyIndex().also { sharedCaches.nineKey = it }
    }

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
        val whole = ranked(nineFullBucket(lockedCode + digits)).filter { fits(it, digits.length) }
        val singlesOnly = locked.isEmpty() && whole.isNotEmpty() && whole.all { it.raw.size == 1 }
        if (singlesOnly && digits.length >= 2) {
            // The first few, and the top character of every syllable the digits spell (586 → 落, 论,
            // 军, 困, 括), so a less used syllable is not pushed behind the 简拼 words. Then the
            // other characters and the 简拼 words by how often they are used, 简拼 counting a little
            // less: 54 lists 及 and 机 before 机构.
            val firstOfEach = whole.distinctBy { it.raw[0] }.toHashSet()
            val heads = whole.filterIndexed { i, entry -> i < SINGLES_BEFORE_INITIALS || entry in firstOfEach }
            take(heads.asSequence(), digits.length, heads.size)
            val shortcuts = ranked(nineInitialsBucket(digits)).filter { it.raw.size == digits.length }.take(INITIALS_LIMIT)
            val rest = whole.filter { it !in heads }
            var s = 0
            for (single in rest) {
                while (s < shortcuts.size && score(shortcuts[s]) - INITIALS_PENALTY > score(single)) take(sequenceOf(shortcuts[s++]), digits.length, 1)
                take(sequenceOf(single), digits.length, 1)
            }
            take(shortcuts.asSequence().drop(s), digits.length, INITIALS_LIMIT)
        }
        take(whole.asSequence(), digits.length, FULL_LIMIT)
        // Then every other character, however many share the digits (5426: jian, lian, jiao…).
        take(whole.asSequence().filter { it.raw.size == 1 }, digits.length, whole.size)
        // The last syllable still being typed: complete it from every syllable code it starts.
        val growing = ArrayList<Entry>()
        for (split in digits.length - 1 downTo maxOf(0, digits.length - MAX_CODE_LENGTH + 1)) {
            val tail = digits.substring(split)
            val head = lockedCode + digits.substring(0, split)
            for (code in index.syllablesByCode.keys) {
                if (code.length <= tail.length || !code.startsWith(tail)) continue
                ranked(nineFullBucket(head + code)).forEach { entry ->
                    if (nineKeyCode(entry.raw.last()) == code && fits(entry, digits.length)) growing += entry
                }
            }
        }
        take(growing.sortedWith(bestFirst).asSequence(), digits.length, FULL_LIMIT)
        // 简拼: one digit per character.
        if (locked.isEmpty() && digits.length >= 2) {
            take(ranked(nineInitialsBucket(digits)).asSequence().filter { it.raw.size == digits.length }, digits.length, INITIALS_LIMIT)
        }
        // A leading part of the digits; the rest stays for the next pick.
        for (used in digits.length - 1 downTo 1) {
            val entries = ranked(nineFullBucket(lockedCode + digits.substring(0, used))).asSequence().filter { fits(it, used) }
            take(entries, used, if (used <= MAX_CODE_LENGTH) SINGLE_LIMIT else PARTIAL_LIMIT)
        }
        // Everything locked and nothing typed after it.
        if (digits.isEmpty() && locked.isNotEmpty()) {
            take(ranked(nineFullBucket(lockedCode)).asSequence().filter { it.raw.size == locked.size && fits(it, 0) }, 0, FULL_LIMIT)
        }
        return result
    }

    /** Syllables a run of digits can start with, longest and most used first, then its letters. */
    fun nineKeySpellings(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()
        val index = nineKeyIndex()
        val result = ArrayList<String>()
        for (length in minOf(MAX_CODE_LENGTH, digits.length) downTo 1) {
            val code = digits.substring(0, length)
            val base = index.syllablesByCode[code].orEmpty()
            if (learning.values.isEmpty()) {
                result += base
            } else {
                val singles = nineFullBucket(code).filter { it.raw.size == 1 }
                val best = singles.groupBy { it.raw[0] }.mapValues { (_, entries) -> entries.minWith(bestFirst) }
                val spellings = (base + best.keys).distinct()
                result += spellings.sortedWith(compareBy<String> { best[it]?.let(::score)?.unaryMinus() ?: Float.POSITIVE_INFINITY }
                    .thenBy { base.indexOf(it).let { at -> if (at < 0) Int.MAX_VALUE else at } })
            }
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
        val entry = (characters()[char].orEmpty() + learning.additions.filter { it.text == char })
            .minWithOrNull(bestFirst) ?: return null
        return score(entry) to entry.raw
    }

    private val characterIndex: HashMap<String, List<Entry>>? get() = sharedCaches.characters

    private fun characters(): HashMap<String, List<Entry>> = characterIndex ?: synchronized(sharedCaches) {
        sharedCaches.characters ?: HashMap<String, List<Entry>>().also { map ->
            for (entry in exact.values.asSequence().flatten()) {
                if (entry.text.length == 1) {
                    map[entry.text] = map[entry.text].orEmpty() + entry
                }
            }
            sharedCaches.characters = map
        }
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
        PinyinDictionary(PinyinFuzzy(Collections.unmodifiableSet(HashSet(rules))), rows, ArrayList(extraRows)).also { it.languageModel = languageModel }

    companion object {
        /** About the weight of an ordinary two-character word. */
        private val NEW_WORD_BASE = ln(20_000f)

        /** One pick multiplies a word's weight by ~16, three by ~256. */
        private const val LEARN_BONUS = 4f

        private val BASE_FIRST = compareByDescending<Entry> { it.base }.thenBy { it.rank }


        private const val MAX_CONTEXT_WORD = 6
        private const val FULL_LIMIT = 30
        private const val INITIALS_LIMIT = 10
        private const val SINGLES_BEFORE_INITIALS = 6
        /** How much less a 简拼 word counts than a character spelled in full (ln 4: a quarter). */
        private val INITIALS_PENALTY = ln(4f)
        /** How much less a word read only through a fuzzy rule counts, per syllable (ln 200). */
        private val FUZZY_PENALTY = ln(200f)
        /** A predicted next word under this is a guess no better than chance (ln 1/400). */
        private val MIN_NEXT_WORD_LOG_P = ln(1f / 400)
        private const val MIN_NEXT_WORD_UNIGRAM = -11f
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
                    rows += Row(text, Collections.unmodifiableList(ArrayList(syllables)), weight, ids.getOrPut(text) { ids.size })
                }
            }
            return PinyinDictionary(PinyinFuzzy(Collections.unmodifiableSet(HashSet(rules))), rows)
        }
    }
}
