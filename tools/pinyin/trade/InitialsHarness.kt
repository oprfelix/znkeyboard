import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Ranks the commonest 2–4 character words when typed by initials alone, as people use 简拼. */
fun main(args: Array<String>) {
    val (dict, lm) = args
    val model = RandomAccessFile(lm, "r").use { PinyinLanguageModel.load(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())) }
    val dictionary = File(dict).bufferedReader().use { PinyinDictionary.load(it, emptySet()) }
    dictionary.languageModel = model
    File(dict).resolveSibling("trade.tsv").bufferedReader().use(dictionary::addWords)
    class Case(val text: String, val input: String, val weight: Double)
    val all = File(dict).readLines().map { it.split('\t') }
        .filter { it[0].length in 2..4 && it[1].split(' ').size == it[0].length }
        .map { Case(it[0], it[1].split(' ').joinToString("") { s -> s.take(1) }, it[2].toDouble()) }
    for (length in 2..4) {
        val cases = all.filter { it.text.length == length }.take(3000)
        var total = 0.0; var one = 0.0; var three = 0.0; var eight = 0.0; var c1 = 0; var c3 = 0; var c8 = 0
        val misses = ArrayList<String>()
        for (case in cases) {
            val found = PinyinEngine.candidates(case.input, dictionary)
            val rank = found.indexOfFirst { it.text == case.text }
            total += case.weight
            if (rank == 0) { one += case.weight; c1++ }
            if (rank in 0..2) { three += case.weight; c3++ }
            if (rank in 0..7) { eight += case.weight; c8++ }
            if (rank !in 0..2 && misses.size < 14) misses += "${case.text}/${case.input}=${if (rank < 0) "无" else rank + 1}:${found.firstOrNull()?.text}"
        }
        fun pct(x: Double) = "%.1f".format(100 * x / total)
        println("$length 字 ${cases.size} 词  按词频: 第一 ${pct(one)}% 前三 ${pct(three)}% 前八 ${pct(eight)}%  不加权: ${c1 * 100 / cases.size}% ${c3 * 100 / cases.size}% ${c8 * 100 / cases.size}%")
        println("   " + misses.joinToString(" "))
    }
}
