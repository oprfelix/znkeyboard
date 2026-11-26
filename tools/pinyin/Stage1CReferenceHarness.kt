import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import dev.zain.znkeyboard.pinyin.SentenceDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

private fun scores(texts: List<String>) = FloatArray(texts.size) { at ->
    ((texts[at].hashCode() xor (at * 17)) % 1000) / 113f
}

private fun inputs(file: File) = file.useLines { lines ->
    lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("set\t") }
        .map { it.split('\t')[2] }.toList()
}

fun main(args: Array<String>) {
    val dictionary = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, PinyinFuzzyRule.DEFAULTS) }
    File(args[0]).resolveSibling("trade.tsv").bufferedReader().use(dictionary::addWords)
    val model = RandomAccessFile(args[1], "r").use { file ->
        PinyinLanguageModel.load(file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
    }
    dictionary.languageModel = checkNotNull(model)
    val scorer = PinyinEngine.SentenceScorer(::scores)
    File(args[3]).bufferedWriter().use { out ->
        inputs(File(args[2])).forEachIndexed { at, input ->
            val before = if (at % 17 == 0) SentenceDecoder.Context("中国", 0) else null
            val taps = if (at % 19 == 0) SentenceDecoder.Taps { FloatArray(26) { key -> key / 37f } } else null
            out.appendLine("$at\t$input\t${PinyinEngine.candidates(input, dictionary, taps, before, scorer)}")
        }
    }
}
