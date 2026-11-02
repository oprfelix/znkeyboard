package dev.zain.znkeyboard.pinyin

/**
 * Learns from pinyin commits and suggests what comes next. Main-thread only; pure Kotlin so
 * `tools/pinyin/verify_pinyin.sh` can drive it.
 *
 * - Every pick boosts that word for its pinyin.
 * - Converting one composition with several picks, at least one of them chosen by hand (tap
 *   or number key), learns the joined phrase: 张 + 三丰 teaches 张三丰 for zhangsanfeng and zsf.
 *   Space-only conversions are not joined, since the user never chose the split.
 * - Consecutive commits teach which word follows which, for predictions after a commit.
 */
class PinyinLearner(var data: PinyinUserData) {
    /** A word to offer after a commit. [word] is set when it completes a dictionary word. */
    class Prediction(val text: String, val word: PinyinDictionary.Word?)

    var dictionary: PinyinDictionary? = null

    /** False in fields that opt out of learning and when the user turned learning off. */
    var learningEnabled = true

    /** The last word committed, while the caret is still right after it. */
    var previous: String? = null
        private set

    /** The dictionary id of the last word picked (-1 if none or unknown): context for the model. */
    private var previousId = -1

    /** The last word committed, for reading the next input on from it; null after a break. */
    val context: SentenceDecoder.Context?
        get() = previous?.let { SentenceDecoder.Context(it, previousId) }

    private val phraseText = StringBuilder()
    private val phraseSyllables = ArrayList<String>()
    private var phrasePicks = 0
    private var phraseChosenByHand = false

    /** One candidate committed from the current composition; [id] is its dictionary word's. */
    fun onPick(text: String, syllables: List<String>, byHand: Boolean, id: Int = -1) {
        if (learningEnabled) {
            learnWord(text, syllables)
            previous?.let { learnPair(it, text) }
        }
        phraseText.append(text)
        phraseSyllables += syllables
        phrasePicks++
        phraseChosenByHand = phraseChosenByHand || byHand
        previous = text
        previousId = id
    }

    /** The composition is fully converted. */
    fun onCompositionDone() {
        if (learningEnabled && phrasePicks >= 2 && phraseChosenByHand && phraseSyllables.size <= MAX_PHRASE_SYLLABLES) {
            val phrase = phraseText.toString()
            learnWord(phrase, phraseSyllables.toList())
            // Pairs follow the phrase; the model keeps its last word as context, which it knows.
            previous = phrase
        }
        resetPhrase()
    }

    /** The composition ended some other way (raw letters, editor dropped it). */
    fun onCompositionAbandoned() {
        resetPhrase()
        previous = null
        previousId = -1
    }

    fun onPredictionPicked(prediction: Prediction) {
        val word = prediction.word
        if (word != null) {
            if (learningEnabled) learnWord(word.text, word.syllables)
            previous = word.text
            previousId = word.id
        } else {
            if (learningEnabled) previous?.let { learnPair(it, prediction.text) }
            previous = prediction.text
            previousId = -1
        }
    }

    /** The user asked to forget [text]: its picks, its built phrase and its predictions go. */
    fun forget(text: String) {
        data.forgetWord(text)
        dictionary?.forget(text)
        if (previous == text) {
            previous = null
            previousId = -1
        }
    }

    /** Something other than pinyin was typed, or the caret moved: the next word has no context. */
    fun breakChain() {
        previous = null
        previousId = -1
    }

    /** Words the user typed after [previous] before, then continuations from the dictionary. */
    fun predictions(limit: Int): List<Prediction> {
        val context = previous ?: return emptyList()
        val result = ArrayList<Prediction>()
        val seen = HashSet<String>()
        for (next in data.nextWords(context, LEARNED_PREDICTIONS)) {
            if (seen.add(next)) result += Prediction(next, null)
        }
        dictionary?.continuations(context, limit)?.forEach { continuation ->
            if (result.size < limit && seen.add(continuation.suffix)) {
                result += Prediction(continuation.suffix, continuation.word)
            }
        }
        return result
    }

    /** Re-applies learned words ([words], all by default) and every pair, e.g. to a dictionary rebuilt for new fuzzy rules. */
    fun applyTo(dictionary: PinyinDictionary, words: Collection<PinyinUserData.Word> = data.words()) {
        for (word in words) dictionary.learn(word.text, word.syllables, word.uses)
        for ((previous, next, uses) in data.pairs()) dictionary.learnPair(previous, next, uses)
    }

    private fun learnPair(previous: String, next: String) {
        val uses = data.learnPair(previous, next)
        dictionary?.learnPair(previous, next, uses)
    }

    private fun learnWord(text: String, syllables: List<String>) {
        val word = data.learnWord(text, syllables)
        dictionary?.learn(word.text, word.syllables, word.uses)
    }

    private fun resetPhrase() {
        phraseText.setLength(0)
        phraseSyllables.clear()
        phrasePicks = 0
        phraseChosenByHand = false
    }

    companion object {
        const val MAX_PHRASE_SYLLABLES = 6
        private const val LEARNED_PREDICTIONS = 4
    }
}
