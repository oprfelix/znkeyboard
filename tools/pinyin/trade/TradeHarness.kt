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
    println("wrong with them:")
    println("top1 with trade words: ${score(load(true), true)}")
}
