import dev.zain.znkeyboard.pinyin.NineKeyEngine
import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import dev.zain.znkeyboard.pinyin.PinyinLearner
import dev.zain.znkeyboard.pinyin.PinyinUserData
import dev.zain.znkeyboard.pinyin.SentenceDecoder
import dev.zain.znkeyboard.typing.CharLanguageModel
import dev.zain.znkeyboard.typing.TouchDecoder
import dev.zain.znkeyboard.typing.TouchModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Scores the pinyin engine on tools/pinyin/bench/bench.tsv: tools/pinyin/bench/run_bench.sh.
 *
 * For each case the whole input is converted with an empty user history, as on a fresh install:
 * - top1: the first candidate is the expected text and uses up all of the input (one space).
 * - top1~: top1, counting 他 / 她 / 它 as the same (only context outside the clause can tell).
 * - top3: the expected text is among the first three candidates and uses up all of the input.
 * - CER: character edit distance from the expected text to the first candidate that reads all
 *   of the input (none counts as every character wrong), over the expected characters.
 * - none: no candidate reads all of the input.
 * Latency is per keystroke, as typed: every prefix of every Nth case is converted after warm-up.
 *
 * With --taps the decoder also gets each letter's tap (bench.tsv's last column), scored against
 * every key by the keyboard's own [TouchDecoder] with an untrained touch model, as on a fresh
 * install.
 *
 * With --learn (and --set to pick one set, since every clause is in all four) the cases are a
 * user's typing, in order: after each, the learner is taught the right words, as if the user
 * picked them (by hand when the first candidate was wrong). Rows "first half" and "second half"
 * show what that learning changed.
 *
 * With --nine each case is typed on the 9-key keyboard instead: its letters become digits
 * ("nihao" → 64426) and go through [NineKeyEngine]. The typo set is left out, its slips being
 * QWERTY ones.
 */
private class Case(val set: String, val bucket: String, val typed: String, val expected: String, val pinyin: String, val taps: String?)

/** Splits [case]'s expected text into the fewest dictionary words that spell its pinyin. */
private fun expectedWords(case: Case, dictionary: PinyinDictionary): List<PinyinDictionary.Word>? {
    val syllables = case.pinyin.split(' ')
    val text = case.expected
    if (syllables.size != text.length) return null
    val best = arrayOfNulls<List<PinyinDictionary.Word>>(syllables.size + 1)
    best[0] = emptyList()
    for (start in syllables.indices) {
        val here = best[start] ?: continue
        for (end in start + 1..minOf(syllables.size, start + 6)) {
            val pieces = syllables.subList(start, end).map { PinyinDictionary.Piece(it, isPrefix = false) }
            val word = dictionary.matches(pieces, 200).firstOrNull { it.text == text.substring(start, end) } ?: continue
            if (best[end] == null || best[end]!!.size > here.size + 1) best[end] = here + word
        }
    }
    return best[syllables.size]
}

/** The keyboard build_bench.py taps on: 411 dp wide, keys 40.5 × 54 dp, rows offset 0, ½ and 1½ keys. */
private object BenchKeyboard {
    private const val WIDTH = 411f
    private const val PAD = 6f
    private const val GAP = 6f
    const val ROW_PITCH = 54f
    const val PITCH = (WIDTH - 2 * PAD + GAP) / 10f
    val centersX = FloatArray(26)
    val centersY = FloatArray(26)

    init {
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, letters ->
            val left = PAD + floatArrayOf(0f, 0.5f, 1.5f)[row] * PITCH
            letters.forEachIndexed { i, letter ->
                centersX[letter - 'a'] = left + i * PITCH + (PITCH - GAP) / 2
                centersY[letter - 'a'] = ROW_PITCH * (row + 0.5f)
            }
        }
    }
}

private val untrainedModel = TouchModel()
private val touchDecoder = TouchDecoder({ untrainedModel }, CharLanguageModel())

private fun tapsOf(case: Case): SentenceDecoder.Taps? {
    val taps = case.taps?.takeIf { it.isNotEmpty() } ?: return null
    val costs = taps.split(';').map { tap ->
        val (x, y) = tap.split(',').map(String::toFloat)
        FloatArray(26).also {
            touchDecoder.letterCosts(x, y, BenchKeyboard.centersX, BenchKeyboard.centersY, BenchKeyboard.PITCH, BenchKeyboard.ROW_PITCH, it)
        }
    }
    check(costs.size == case.typed.length) { "${case.typed}: ${costs.size} taps" }
    return SentenceDecoder.Taps { at -> costs.getOrNull(at) }
}

private class Tally {
    var cases = 0
    var top1 = 0
    var top1Pronoun = 0
    var top3 = 0
    var none = 0
    var edits = 0
    var chars = 0

    fun add(top1: Boolean, top1Pronoun: Boolean, top3: Boolean, none: Boolean, edits: Int, chars: Int) {
        cases++
        if (top1) this.top1++
        if (top1Pronoun) this.top1Pronoun++
        if (top3) this.top3++
        if (none) this.none++
        this.edits += edits
        this.chars += chars
    }

    fun row(name: String): String {
        fun pct(n: Int, of: Int) = "%6.1f".format(100.0 * n / of.coerceAtLeast(1))
        return "%-18s %5d %s %s %s %s %s".format(name, cases, pct(top1, cases), pct(top1Pronoun, cases), pct(top3, cases), pct(edits, chars), pct(none, cases))
    }
}

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        previous = current
    }
    return previous[b.length]
}

private fun sameReading(a: String, b: String) = a.replace('她', '他').replace('它', '他') == b.replace('她', '他').replace('它', '他')

private fun percentile(sorted: List<Double>, p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]

fun main(args: Array<String>) {
    val options = args.drop(2)
    fun option(name: String) = options.indexOf(name).takeIf { it >= 0 }?.let { options.getOrNull(it + 1) }
    val strict = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, emptySet()) }
    // The keyboard starts with every fuzzy sound on; --strict measures without them.
    val dictionary = if ("--strict" in options) strict else strict.withFuzzy(PinyinFuzzyRule.DEFAULTS)
    option("--lm")?.let { path ->
        val buffer = RandomAccessFile(path, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val model = PinyinLanguageModel.load(buffer) ?: error("$path is not a language model")
        dictionary.languageModel = model
        check(dictionary.languageModel != null) { "$path was built for another dictionary" }
    }
    val nine = "--nine" in options
    val latencyEvery = option("--latency-every")?.toInt() ?: 10
    val cases = File(args[1]).readLines()
        .filter { !it.startsWith("#") && !it.startsWith("set\t") && it.isNotBlank() }
        .map { line -> line.split("\t").let { Case(it[0], it[1], it[2], it[3], it[4], it.getOrNull(6)) } }
        .filter { case -> option("--set")?.let { it == case.set } ?: true }
        .filter { case -> !nine || case.set != "typo" }
    val learner = if ("--learn" in options) PinyinLearner(PinyinUserData()).also { it.dictionary = dictionary } else null
    val useTaps = "--taps" in options
    require(!(nine && useTaps)) { "--taps are QWERTY taps; leave them out with --nine" }
    val tapsFor = cases.associateWith { if (useTaps) tapsOf(it) else null }
    fun inputOf(case: Case) = if (nine) PinyinDictionary.nineKeyCode(case.typed) else case.typed
    fun convert(input: String, case: Case) =
        if (nine) NineKeyEngine.candidates(input, dictionary) else PinyinEngine.candidates(input, dictionary, tapsFor[case])

    // Warm-up, so the JIT doesn't count against the first cases.
    for (case in cases.take(300)) convert(inputOf(case), case)

    val tallies = LinkedHashMap<String, Tally>()
    val keystrokes = ArrayList<Double>()
    val lines = ArrayList<String>()
    for ((index, case) in cases.withIndex()) {
        val input = inputOf(case)
        val candidates = convert(input, case)
        val whole = candidates.filter { it.consumed >= input.length }
        val first = candidates.firstOrNull()
        val top1 = first != null && first.text == case.expected && first.consumed >= input.length
        val top1Pronoun = first != null && sameReading(first.text, case.expected) && first.consumed >= input.length
        val top3 = candidates.take(3).any { it.text == case.expected && it.consumed >= input.length }
        val reading = whole.firstOrNull()?.text ?: ""
        val edits = editDistance(case.expected, reading).coerceAtMost(case.expected.length)
        val half = if (learner == null) null else if (index < cases.size / 2) "first half" else "second half"
        for (key in listOfNotNull("${case.set} ${case.bucket}", "${case.set} all", "all", half)) {
            tallies.getOrPut(key) { Tally() }.add(top1, top1Pronoun, top3, whole.isEmpty(), edits, case.expected.length)
        }
        val rank = candidates.indexOfFirst { it.text == case.expected && it.consumed >= input.length }
        lines += listOf(case.set, case.bucket, input, case.expected, reading, if (rank < 0) "-" else "${rank + 1}").joinToString("\t")
        if (learner != null) {
            expectedWords(case, dictionary)?.forEach { learner.onPick(it.text, it.syllables, byHand = !top1, id = it.id) }
            learner.onCompositionDone()
            learner.breakChain()
        }

        if (latencyEvery > 0 && index % latencyEvery == 0) {
            for (end in 1..input.length) {
                val started = System.nanoTime()
                convert(input.substring(0, end), case)
                keystrokes += (System.nanoTime() - started) / 1e6
            }
        }
    }

    println("fuzzy sounds: " + (if ("--strict" in options) "off" else "default (all on)") + "; language model: " + (option("--lm") ?: "none") + "; taps: " + (if (useTaps) "yes" else "no") + (if (nine) "; 9-key" else "") + if (learner != null) "; learning as it goes" else "")
    println("%-18s %5s %6s %6s %6s %6s %6s".format("set", "cases", "top1%", "top1~%", "top3%", "CER%", "none%"))
    val sets = cases.map { it.set }.distinct()
    for (set in sets) {
        for (bucket in cases.map { it.bucket }.distinct()) tallies["$set $bucket"]?.let { println(it.row("$set $bucket")) }
        println(tallies.getValue("$set all").row("$set all"))
        println()
    }
    println(tallies.getValue("all").row("all"))
    if (learner != null) for (half in listOf("first half", "second half")) tallies[half]?.let { println(it.row("learning, $half")) }
    if (keystrokes.isNotEmpty()) {
        val sorted = keystrokes.sorted()
        println("per keystroke (%d timed): mean %.2f ms, p50 %.2f, p95 %.2f, max %.2f".format(
            sorted.size, sorted.average(), percentile(sorted, 0.5), percentile(sorted, 0.95), sorted.last()))
    }
    option("--cases")?.let { path ->
        File(path).writeText("set\tbucket\ttyped\texpected\tgot\trank\n" + lines.joinToString("\n") + "\n")
        println("cases written to $path")
    }
}
