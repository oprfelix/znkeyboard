import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinFuzzyRule
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
