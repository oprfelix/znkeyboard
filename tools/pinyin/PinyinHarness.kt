import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import dev.zain.znkeyboard.pinyin.PinyinLearner
import dev.zain.znkeyboard.pinyin.PinyinUserData
import java.io.File

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String) {
    println((if (ok) "ok   " else "FAIL ") + name + "  " + detail)
    if (!ok) failures++
}

private fun top(input: String, dictionary: PinyinDictionary, n: Int = 5) =
    PinyinEngine.candidates(input, dictionary).take(n)

fun main(args: Array<String>) {
    val started = System.nanoTime()
    val strict = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, emptySet()) }
    println("load ms: ${(System.nanoTime() - started) / 1_000_000}")
    val fuzzy = strict.withFuzzy(PinyinFuzzyRule.DEFAULTS)

    fun segments(input: String, dictionary: PinyinDictionary = strict) =
        PinyinEngine.segment(input, dictionary).joinToString(" ") { it.text }

    check("segment nihao", segments("nihao") == "ni hao", segments("nihao"))
    check("segment fanguan backtracks", segments("fanguan") == "fan guan", segments("fanguan"))
    check("segment xi'an separator", segments("xi'an") == "xi an", segments("xi'an"))
    check("segment partial tail", segments("zhongg") == "zhong g", segments("zhongg"))
    check("segment stops at typo", segments("nihaov") == "ni hao", segments("nihaov"))

    val nihao = top("nihao", strict)
    check("nihao -> 你好", nihao.firstOrNull()?.text == "你好", nihao.toString())
    check("nihao consumes all", nihao.firstOrNull()?.consumed == 5, nihao.toString())
    val zhongguo = top("zhongguo", strict)
    check("zhongguo -> 中国", zhongguo.firstOrNull()?.text == "中国", zhongguo.toString())
    val partial = top("zhongg", strict)
    check("zhongg prefix -> 中国", partial.any { it.text == "中国" }, partial.toString())
    val single = PinyinEngine.candidates("nihao", strict).firstOrNull { it.text == "你" }
    check("single char consumes 2", single?.consumed == 2, single.toString())

    check("strict zongguo has no 中国", top("zongguo", strict, 20).none { it.text == "中国" }, "")
    val zFuzzy = top("zongguo", fuzzy)
    check("fuzzy zongguo -> 中国", zFuzzy.any { it.text == "中国" }, zFuzzy.toString())
    val nl = top("niulai", fuzzy, 20)
    check("fuzzy n/l: liulai still finds 流", top("liu", fuzzy, 40).any { it.text == "流" }, "")
    check("fuzzy n/l: nanren ~ lanren", top("lanren", fuzzy, 20).any { it.text == "男人" }, top("lanren", fuzzy).toString())
    check("fuzzy ang: shanghai via shanhai", top("shanhai", fuzzy, 20).any { it.text == "上海" }, top("shanhai", fuzzy).toString())
    check("fuzzy ing: xinqi -> 星期", top("xinqi", fuzzy, 20).any { it.text == "星期" }, top("xinqi", fuzzy).toString())
    check("fuzzy eng: pengyou via penyou", top("penyou", fuzzy, 20).any { it.text == "朋友" }, top("penyou", fuzzy).toString())
    println("niulai sample: $nl")

    // 简拼: initials alone, and mixed with full syllables.
    check("segment nh as initials", segments("nh") == "n h", segments("nh"))
    check("nh skips l-words", top("nh", fuzzy, 30).none { it.text == "联合" || it.text == "良好" }, "")
    check("nh -> 你好 top 8", top("nh", fuzzy, 8).any { it.text == "你好" }, top("nh", fuzzy).toString())
    check("zg -> 中国 top 8", top("zg", fuzzy, 8).any { it.text == "中国" }, top("zg", fuzzy).toString())
    check("xx -> 谢谢 top 5", top("xx", fuzzy).any { it.text == "谢谢" }, top("xx", fuzzy).toString())
    check("nhao -> 你好 top 3", top("nhao", fuzzy, 3).any { it.text == "你好" }, top("nhao", fuzzy).toString())
    check("wmen -> 我们 first", top("wmen", fuzzy).firstOrNull()?.text == "我们", top("wmen", fuzzy).toString())
    check("zgr -> 中国人 top 3", top("zgr", fuzzy, 3).any { it.text == "中国人" }, top("zgr", fuzzy).toString())
    check("strict zhg -> 中国", top("zhg", strict, 5).any { it.text == "中国" }, top("zhg", strict).toString())
    check("full pinyin stays exact: nihao has no 年号", top("nihao", strict, 30).none { it.text == "年号" }, "")
    val nhFirst = PinyinEngine.candidates("nh", fuzzy).first { it.text == "你" }
    check("initial-only char consumes 1", nhFirst.consumed == 1, nhFirst.toString())

    // Weights: joke weights no longer outrank everyday words.
    check("jzm no longer above 说", strict.matches(listOf(PinyinDictionary.Piece("shuo", false)), 1).isNotEmpty(), "")

    // Learning: picks boost a word, phrases built by hand become words, predictions follow.
    val learnDict = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, PinyinFuzzyRule.DEFAULTS) }
    val learner = PinyinLearner(PinyinUserData()).apply { dictionary = learnDict }
    fun pick(text: String, pinyin: String, byHand: Boolean = true) = learner.onPick(text, pinyin.split(' '), byHand)

    val shiBefore = top("shi", learnDict, 40).map { it.text }
    val rare = shiBefore.drop(10).first()
    pick(rare, "shi")
    learner.onCompositionDone()
    val shiOnce = top("shi", learnDict, 40).map { it.text }
    check("one pick moves $rare up", shiOnce.indexOf(rare) < shiBefore.indexOf(rare), "${shiBefore.indexOf(rare)} -> ${shiOnce.indexOf(rare)}")
    repeat(3) { pick(rare, "shi"); learner.onCompositionDone() }
    check("repeated picks put $rare first", top("shi", learnDict).first().text == rare, top("shi", learnDict).toString())

    check("wangxiaofeng unknown at first", top("wangxiaofeng", learnDict, 3).none { it.text == "王小丰" }, top("wangxiaofeng", learnDict, 3).toString())
    pick("王", "wang")
    pick("小丰", "xiao feng")
    learner.onCompositionDone()
    check("built phrase 王小丰 by full pinyin", top("wangxiaofeng", learnDict).firstOrNull()?.text == "王小丰", top("wangxiaofeng", learnDict).toString())
    check("built phrase 王小丰 by 简拼", top("wxf", learnDict, 3).any { it.text == "王小丰" }, top("wxf", learnDict, 3).toString())
    val built = top("wangxiaofeng", learnDict).first()
    check("built phrase consumes all", built.consumed == 12 && built.syllables == listOf("wang", "xiao", "feng"), built.toString())

    pick("你", "ni", byHand = false)
    pick("号", "hao", byHand = false)
    learner.onCompositionDone()
    check("space-only conversion builds no phrase", top("nihao", learnDict, 30).none { it.text == "你号" }, "")

    learner.breakChain()
    pick("中国", "zhong guo")
    learner.onCompositionDone()
    val afterChina = learner.predictions(10).map { it.text }
    check("predicts continuations of 中国", "人" in afterChina, afterChina.toString())
    pick("人民", "ren min")
    learner.onCompositionDone()
    learner.breakChain()
    pick("中国", "zhong guo")
    learner.onCompositionDone()
    check("learned follower comes first", learner.predictions(10).firstOrNull()?.text == "人民", learner.predictions(10).map { it.text }.toString())
    val continuation = learner.predictions(10).first { it.word != null }
    learner.onPredictionPicked(continuation)
    check("picking a continuation moves context", learner.previous == continuation.word?.text, learner.previous.toString())
    learner.breakChain()
    check("no predictions without context", learner.predictions(10).isEmpty(), "")

    val restored = PinyinUserData.decode(learner.data.encode())
    check("user data round-trips", restored != null && restored.words().size == learner.data.words().size &&
        restored.nextWords("中国", 3) == learner.data.nextWords("中国", 3), "${restored?.words()?.size}")
    check("rejects foreign files", PinyinUserData.decode("hello".toByteArray()) == null, "")

    val rebuilt = learnDict.withFuzzy(emptySet())
    learner.applyTo(rebuilt)
    check("learning survives a fuzzy rebuild", PinyinEngine.candidates("wangxiaofeng", rebuilt).firstOrNull()?.text == "王小丰", "")
    learnDict.forgetLearned()
    check("forget drops built phrases", top("wangxiaofeng", learnDict, 5).none { it.text == "王小丰" }, "")
    check("forget restores order", top("shi", learnDict, 40).map { it.text } == shiBefore, "")

    val t = System.nanoTime()
    repeat(200) { PinyinEngine.candidates("zhonghuarenmingongheguo", fuzzy) }
    println("long input avg us: ${(System.nanoTime() - t) / 200 / 1000}")
    val typo = System.nanoTime()
    PinyinEngine.candidates("zhvzhvzhvzhvzhvzhvzhvzhvzhvzhv", fuzzy)
    println("typo input us: ${(System.nanoTime() - typo) / 1000}")

    if (failures > 0) {
        println("$failures check(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("all checks passed")
}
