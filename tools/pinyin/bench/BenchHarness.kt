import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import java.io.File

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
 */
private class Case(val set: String, val bucket: String, val typed: String, val expected: String)

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
    val latencyEvery = option("--latency-every")?.toInt() ?: 10
    val cases = File(args[1]).readLines()
        .filter { !it.startsWith("#") && !it.startsWith("set\t") && it.isNotBlank() }
        .map { line -> line.split("\t").let { Case(it[0], it[1], it[2], it[3]) } }

    // Warm-up, so the JIT doesn't count against the first cases.
    for (case in cases.take(300)) PinyinEngine.candidates(case.typed, dictionary)

    val tallies = LinkedHashMap<String, Tally>()
    val keystrokes = ArrayList<Double>()
    val lines = ArrayList<String>()
    for ((index, case) in cases.withIndex()) {
        val candidates = PinyinEngine.candidates(case.typed, dictionary)
        val whole = candidates.filter { it.consumed >= case.typed.length }
        val first = candidates.firstOrNull()
        val top1 = first != null && first.text == case.expected && first.consumed >= case.typed.length
        val top1Pronoun = first != null && sameReading(first.text, case.expected) && first.consumed >= case.typed.length
        val top3 = candidates.take(3).any { it.text == case.expected && it.consumed >= case.typed.length }
        val reading = whole.firstOrNull()?.text ?: ""
        val edits = editDistance(case.expected, reading).coerceAtMost(case.expected.length)
        for (key in listOf("${case.set} ${case.bucket}", "${case.set} all", "all")) {
            tallies.getOrPut(key) { Tally() }.add(top1, top1Pronoun, top3, whole.isEmpty(), edits, case.expected.length)
        }
        val rank = candidates.indexOfFirst { it.text == case.expected && it.consumed >= case.typed.length }
        lines += listOf(case.set, case.bucket, case.typed, case.expected, reading, if (rank < 0) "-" else "${rank + 1}").joinToString("\t")

        if (latencyEvery > 0 && index % latencyEvery == 0) {
            for (end in 1..case.typed.length) {
                val started = System.nanoTime()
                PinyinEngine.candidates(case.typed.substring(0, end), dictionary)
                keystrokes += (System.nanoTime() - started) / 1e6
            }
        }
    }

    println("fuzzy sounds: " + if ("--strict" in options) "off" else "default (all on)")
    println("%-18s %5s %6s %6s %6s %6s %6s".format("set", "cases", "top1%", "top1~%", "top3%", "CER%", "none%"))
    val sets = cases.map { it.set }.distinct()
    for (set in sets) {
        for (bucket in cases.map { it.bucket }.distinct()) tallies["$set $bucket"]?.let { println(it.row("$set $bucket")) }
        println(tallies.getValue("$set all").row("$set all"))
        println()
    }
    println(tallies.getValue("all").row("all"))
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
