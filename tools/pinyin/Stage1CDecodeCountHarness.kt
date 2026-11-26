import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import dev.zain.znkeyboard.pinyin.SentenceDecoder
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

fun main(args: Array<String>) {
    val dictionary = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, PinyinFuzzyRule.DEFAULTS) }
    val model = RandomAccessFile(args[1], "r").use { file ->
        PinyinLanguageModel.load(file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
    }
    dictionary.languageModel = checkNotNull(model)
    val scorer = PinyinEngine.SentenceScorer { texts -> FloatArray(texts.size) { it / 13f } }
    SentenceDecoder.stage1cDecodeCalls = 0
    PinyinEngine.candidates("nihao", dictionary, scorer = scorer)
    PinyinEngine.candidates("nihao", dictionary, scorer = scorer)
    check(SentenceDecoder.stage1cDecodeCalls == 2)
    val old = SentenceDecoder.stage1cDecodeCalls
    SentenceDecoder.stage1cDecodeCalls = 0
    val snapshot = PinyinEngine.prepareCandidates("nihao", dictionary.snapshotView(), aiEnabled = true)
    check(SentenceDecoder.stage1cDecodeCalls == 1)
    snapshot.candidates()
    snapshot.candidates(FloatArray(snapshot.texts.size) { it / 13f })
    check(SentenceDecoder.stage1cDecodeCalls == 1)
    println("SentenceDecoder.decodeAll calls: old base+AI=$old; snapshot base+AI=${SentenceDecoder.stage1cDecodeCalls}")
}
