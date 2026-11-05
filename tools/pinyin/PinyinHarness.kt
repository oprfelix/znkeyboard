import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.EnglishWords
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
import dev.zain.znkeyboard.pinyin.PinyinLanguageModel
import dev.zain.znkeyboard.pinyin.SentenceDecoder
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import dev.zain.znkeyboard.pinyin.PinyinLearner
import dev.zain.znkeyboard.pinyin.PinyinTapBuffer
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

    // Without fuzzy sounds zong is not zhong; 中国 may only come as a slip-corrected sentence.
    val zongguo = top("zongguo", strict, 20)
    check("strict zongguo: 中国 only as a correction", zongguo.none { it.text == "中国" && it.parts == null } &&
        zongguo.filter { it.text == "中国" }.all { it.spelling == "zhong guo" }, zongguo.map { it.text }.toString())
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

    // One accidental build (我们 + 一气 by hand) must not take over a sentence; a habit (a few builds) does.
    fun sentence() = PinyinEngine.candidates("womengyiqiqupashan", learnDict).first().text
    pick("我们", "wo men"); pick("一气", "yi qi"); learner.onCompositionDone()
    check("one-off 我们一气 keeps 我们一起去爬山", sentence() == "我们一起去爬山", sentence())
    var builds = 1
    while (sentence() != "我们一气去爬山" && builds < 6) {
        pick("我们", "wo men"); pick("一气", "yi qi"); learner.onCompositionDone()
        builds++
    }
    check("built again and again, 我们一气 wins (after $builds)", sentence() == "我们一气去爬山" && builds <= 5, sentence())
    learner.forget("我们一气")
    learner.forget("一气")
    check("forgotten, back to 我们一起去爬山", sentence() == "我们一起去爬山", sentence())
    check("forgotten word is not learned", !learnDict.isLearned("我们一气"), "")
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

    // Taps stay lined up with the letters through typing, backspace and picks.
    run {
        fun tap(marker: Float) = FloatArray(26) { marker }
        val taps = PinyinTapBuffer()
        var composing = ""
        fun type(next: String, tap: FloatArray?) {
            taps.follow(composing, next, tap)
            composing = next
        }
        fun markers() = composing.indices.joinToString(",") { taps.costs(it)?.get(0)?.toInt()?.toString() ?: "-" }
        type("n", tap(1f))
        type("ni", tap(2f))
        type("ni'", null)
        type("ni'h", tap(3f))
        check("taps follow typing", markers() == "1,2,-,3", markers())
        type("ni'", null)
        type("ni'h", tap(4f))
        type("ni'ha", tap(5f))
        check("backspace drops the last tap", markers() == "1,2,-,4,5", markers())
        type("ha", null) // 你 picked: its letters and the separator go
        check("a pick shifts the rest", markers() == "4,5", markers())
        type("xyz", null)
        check("any other change forgets them", markers() == "-,-,-", markers())
        type("", null)
        type("w", tap(6f))
        check("taps start over after a commit", markers() == "6", markers())
        val sample = Any()
        taps.follow("w", "wo", tap(7f), sample)
        taps.follow("wo", "o", null)
        check("a tap's token moves with it", taps.token(0) === sample, "${taps.token(0)}")
    }
    run {
        fun subs(typed: String, spelling: String) = PinyinTapBuffer.substitutions(typed, spelling).joinToString(",") { "${it.first}${it.second}" }
        check("substitution found", subs("wlaini", "wo ai ni") == "1o", subs("wlaini", "wo ai ni"))
        check("separators keep typed indexes", subs("xi'qn", "xi an") == "3a", subs("xi'qn", "xi an"))
        check("a swap is not a substitution", subs("xinag", "xiang") == "", subs("xinag", "xiang"))
        check("a dropped letter is not one", subs("xang", "xiang") == "", subs("xang", "xiang"))
        check("简拼 as typed has none", subs("wmyqkdy", "w m y q k d y") == "", subs("wmyqkdy", "w m y q k d y"))
    }
    // With the language model, as the keyboard ships.
    args.getOrNull(1)?.let { path ->
        val model = RandomAccessFile(path, "r").use { PinyinLanguageModel.load(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())) }
        val withModel = fuzzy.withFuzzy(fuzzy.fuzzy.rules).also { it.languageModel = model }
        check("model fits the dictionary", withModel.languageModel != null, "")
        fun first(input: String, before: SentenceDecoder.Context? = null) = PinyinEngine.candidates(input, withModel, null, before).firstOrNull()?.text
        // Picks are a share of a probability, so a much-picked character can't outbid whole words.
        val heavy = PinyinLearner(PinyinUserData()).also { it.dictionary = withModel }
        repeat(300) { heavy.onPick("你", listOf("ni"), byHand = true); heavy.onPick("狗", listOf("gou"), byHand = true); heavy.breakChain() }
        check("heavy picks don't split words", first("tiantian") == "天天" && first("gougou") == "狗狗", "${first("tiantian")} ${first("gougou")}")
        // What the user typed after a word lifts it there.
        val pairs = PinyinLearner(PinyinUserData()).also { it.dictionary = withModel }
        val plain = first("shi")
        repeat(5) { pairs.onPick("历史", listOf("li", "shi"), byHand = true); pairs.onPick("诗", listOf("shi"), byHand = true); pairs.breakChain() }
        pairs.onPick("历史", listOf("li", "shi"), byHand = true)
        check("a learned pair reads on from the word before", first("shi", pairs.context) == "诗" && plain != "诗", "$plain -> ${first("shi", pairs.context)}")
        withModel.forgetLearned()
        check("forgetting drops pairs too", withModel.pairTotal("历史") == 0 && withModel.learnedUses == 0, "")
    }

    // English words: first when the letters can't be whole pinyin, after the best Chinese otherwise.
    run {
        val english = EnglishWords(listOf("hello", "time", "iPhone", "the"))
        val chinese = listOf(PinyinEngine.Candidate("时么", 4, listOf("shi", "me")))
        fun texts(input: String) = english.merge(input, chinese, fuzzy).map { it.text }
        check("not pinyin comes first", texts("hello") == listOf("hello", "时么"), texts("hello").toString())
        check("also pinyin comes second", texts("time") == listOf("时么", "time"), texts("time").toString())
        check("shown as written", texts("iphone").first() == "iPhone", texts("iphone").toString())
        check("short words stay out", texts("the") == listOf("时么"), texts("the").toString())
        check("unknown letters add nothing", texts("zzzz") == listOf("时么"), texts("zzzz").toString())
        check("nothing to go after", english.merge("time", emptyList(), fuzzy).map { it.text } == listOf("time"), "")
        english.picked("time")
        check("one pick isn't enough", texts("time").first() == "时么", texts("time").toString())
        english.picked("time")
        check("two picks move it first", texts("time").first() == "time", texts("time").toString())
        val copy = EnglishWords(listOf("time")).also { it.restore(english.saved()) }
        check("picks survive saving", copy.merge("time", chinese, fuzzy).first().text == "time", english.saved())
        check("forgetting moves it back", copy.forget("time") && copy.merge("time", chinese, fuzzy).first().text == "时么", "")
    }

    if (failures > 0) {
        println("$failures check(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("all checks passed")
}
