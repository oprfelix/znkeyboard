import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import dev.zain.znkeyboard.pinyin.SentenceDecoder
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Whole-sentence input against the bundled dictionary: tools/pinyin/verify_sentence.sh. */
private val CASES = listOf(
    // Full pinyin.
    "womingtianxiangqupashanyoumeiyourenyiqiqu" to "我明天想去爬山有没有人一起去",
    "jintiantianqizhenhao" to "今天天气真好",
    "woxiangchifan" to "我想吃饭",
    "nihaoshijie" to "你好世界",
    "zhongguorenmin" to "中国人民",
    "womenyiqiqukandianying" to "我们一起去看电影",
    "zheshiyigewenti" to "这是一个问题",
    "wobuzhidaozenmeban" to "我不知道怎么办",
    "xiexienidebangzhu" to "谢谢你的帮助",
    "mingtianjiandao" to "明天见到",
    "xi'an" to "西安",
    "nizaiganshenme" to "你在干什么",
    "wojintianhenmang" to "我今天很忙",
    "zhegedongxihenhaochi" to "这个东西很好吃",
    "dajiahao" to "大家好",
    "qingbangwokanyixia" to "请帮我看一下",
    "haodexiexie" to "好的谢谢",
    "wozaijia" to "我在家",
    "ninhao" to "您好",
    "zhoumoyouhuodongma" to "周末有活动吗",
    "dianhuahaomashiduoshao" to "电话号码是多少",
    // Slips.
    "womintianxangqupashanymyrenyiqqu" to "我明天想去爬山有没有人一起去",
    "jintiantianqizhenhap" to "今天天气真好",
    "woxinagchifan" to "我想吃饭",
    "woxiamgchifan" to "我想吃饭",
    "zhonguorenmin" to "中国人民",
    "wobuzhidaozenmrban" to "我不知道怎么办",
    "nizaigansheme" to "你在干什么",
    "wozaijai" to "我在家",
    "qingbamgwokanyixia" to "请帮我看一下",
    // 简拼 and mixes.
    "ymyren" to "有没有人",
    "wmyqkdy" to "我们一起看电影",
)

/** Needs a language model (phase two): long all-initials input and homophones. Shown, not required. */
private val STRETCH = listOf(
    // Homophones only context can settle (两点 / 亮点, 收到 / 受到).
    "xiawuliangdiankaihui" to "下午两点开会",
    "shoudaole" to "收到了",
    "wmyqqkdy" to "我们一起去看电影",
    "nhsj" to "你好世界",
)

/**
 * Cases the language model reads another way, shown but not required with it on. Each is a
 * near-tie the word-pair model gets wrong: both readings are fine Chinese, or both slips cost the
 * same and the commoner pair wins.
 */
private val MODEL_MISSES = setOf(
    "zhoumoyouhuodongma",               // 周末游 is a word, and 周末游活动 a common pair.
    "womintianxangqupashanymyrenyiqqu", // ymy is 也没有 as much as 有没有.
    "wozaijai",                         // jai is one slip from mai as from jia: 我再买.
)

/** Correct spellings that must not be "fixed": the reading keeps them as typed. */
private val KEEP = listOf("min", "xian", "nin", "fan", "an", "ren", "jin", "shen")

fun main(args: Array<String>) {
    val strict = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, emptySet()) }
    val model = args.getOrNull(1)?.let { path ->
        RandomAccessFile(path, "r").use { PinyinLanguageModel.load(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())) }
            ?: error("$path is not a language model")
    }
    var failures = 0
    // Without fuzzy sounds, then with the defaults the keyboard starts with (s = sh and so on);
    // then both again with the language model, as the keyboard ships.
    val dictionaries = listOf(strict, strict.withFuzzy(PinyinFuzzyRule.DEFAULTS)).let { plain ->
        if (model == null) plain else plain + plain.map { it.withFuzzy(it.fuzzy.rules).also { lm -> lm.languageModel = model } }
    }
    for (dictionary in dictionaries) {
    val withModel = dictionary.languageModel != null
    if (model != null) check(!withModel || dictionary.languageModel === model) { "the model was built for another dictionary" }
    println((if (dictionary.fuzzy.rules.isEmpty()) "== no fuzzy sounds" else "== default fuzzy sounds") + if (withModel) ", language model" else "")
    var worst = 0L
    for (typed in KEEP) {
        val sentence = SentenceDecoder.decode(typed, dictionary)
        val ok = sentence?.spelling == typed
        if (!ok) failures++
        println((if (ok) "ok   " else "FAIL ") + "keeps $typed -> ${sentence?.text} [${sentence?.spelling}]")
    }
    for ((input, expected) in CASES) {
        // As typed: one decode per keystroke, the last one is what counts.
        var sentence: SentenceDecoder.Sentence? = null
        val started = System.nanoTime()
        for (end in 1..input.length) sentence = SentenceDecoder.decode(input.substring(0, end), dictionary)
        val ms = (System.nanoTime() - started) / 1_000_000 / input.length
        if (ms > worst && input != CASES.first().first) worst = ms
        val ok = sentence?.text == expected
        val excused = !ok && withModel && input in MODEL_MISSES
        if (!ok && !excused) failures++
        val mark = if (ok) "ok   " else if (excused) "--   " else "FAIL "
        println(mark + "$input -> ${sentence?.text}  [${sentence?.spelling}]  ${ms}ms" + if (ok) "" else "  (want $expected)")
    }
    for ((input, expected) in STRETCH) {
        val sentence = SentenceDecoder.decode(input, dictionary)
        println((if (sentence?.text == expected) "ok   " else "--   ") + "stretch $input -> ${sentence?.text}  (want $expected)")
    }
    println("slowest per keystroke after warm-up: ${worst}ms")
    }
    println(if (failures == 0) "all passed" else "$failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
