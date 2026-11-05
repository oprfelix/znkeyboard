import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import java.io.File
import java.io.RandomAccessFile

/** Scores whole trade sentences, typed in full pinyin, with and without assets/pinyin/trade.tsv. */
fun main(args: Array<String>) {
    val (dict, lm, cases) = args
    val model = RandomAccessFile(lm, "r").use { PinyinLanguageModel.load(it.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.length())) }
    fun load(trade: Boolean) = File(dict).bufferedReader().use { PinyinDictionary.load(it, emptySet()) }.also {
        it.languageModel = model
        if (trade) File(dict).resolveSibling("trade.tsv").bufferedReader().use(it::addWords)
    }
    val rows = File(cases).readLines().map { it.split('\t') }
    fun score(dictionary: PinyinDictionary, show: Boolean): Int {
        var right = 0
        for ((text, input) in rows) {
            val found = PinyinEngine.candidates(input, dictionary)
            val top = found.firstOrNull()?.text
            if (top == text) right++ else if (show) {
                val rank = found.indexOfFirst { it.text == text }
                println("  $text  →  $top${if (rank >= 0) "  (正确结果排第 ${rank + 1})" else ""}")
            }
        }
        return right
    }
    println("top1 of ${rows.size}: without trade words ${score(load(false), false)}")
    // Each everyday clause cut in two: the second half typed with the first already in the field.
    val dictionary = load(true)
    var plain = 0; var known = 0; var total = 0; var fixed = 0; var broke = 0
    val samples = ArrayList<String>()
    for (line in File(args[3]).readLines()) {
        val c = line.split('\t')
        if (c.size < 5 || c[0] != "full" || c[3].length < 6) continue
        val syllables = c[4].split(' ')
        if (syllables.size != c[3].length) continue
        // Cut where a word of the right reading ends, as a commit would.
        val parts = PinyinEngine.candidates(c[2], dictionary).firstOrNull { it.text == c[3] }?.parts ?: continue
        if (parts.size < 2) continue
        val cut = parts.take(parts.size / 2).sumOf { it.text.length }
        val first = c[3].substring(0, cut); val second = c[3].substring(cut)
        val input = syllables.drop(cut).joinToString("")
        val alone = PinyinEngine.candidates(input, dictionary).firstOrNull()?.text
        val read = PinyinEngine.candidates(input, dictionary, null, dictionary.contextBefore(first)).firstOrNull()?.text
        total++
        if (alone == second) plain++
        if (read == second) known++
        if (alone == second && read != second) broke++
        if (alone != second && read == second) { fixed++; if (samples.size < 6) samples += "对 $first | $alone → $read" }
        if (alone == second && read != second && samples.size < 12) samples += "错 $first | $alone → $read"
    }
    println("second half right: alone $plain, after the first half $known of $total (fixed $fixed, broke $broke)")
    samples.forEach { println("  $it") }
    println("wrong with them:")
    println("top1 with trade words: ${score(load(true), true)}")
}
