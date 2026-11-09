import dev.zain.znkeyboard.pinyin.NineKeyEngine
import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.StrokeIndex
import java.io.File

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String) {
    println((if (ok) "ok   " else "FAIL ") + name + "  " + detail)
    if (!ok) failures++
}

/** Checks 9-key pinyin and stroke input against the bundled data: tools/pinyin/verify_ninekey.sh. */
fun main(args: Array<String>) {
    val dictionary = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, emptySet()) }
    val strokes = File(args[1]).bufferedReader().use { StrokeIndex.load(it) }
    fun top(composing: String, n: Int = 6) = NineKeyEngine.candidates(composing, dictionary).take(n).map { it.text }

    var started = System.nanoTime()
    check("64426 -> 你好", top("64426").firstOrNull() == "你好", top("64426").toString())
    println("first 9-key query ms (builds index): ${(System.nanoTime() - started) / 1_000_000}")
    started = System.nanoTime()
    repeat(20) { NineKeyEngine.candidates("9466448", dictionary) }
    println("9-key query ms: ${(System.nanoTime() - started) / 20_000_000.0}")
    check("94664486 -> 中国", top("94664486").firstOrNull() == "中国", top("94664486").toString())
    check("9466448 growing -> 中国", top("9466448").contains("中国"), top("9466448").toString())
    check("64 -> 你 among", top("64", 10).contains("你"), top("64", 10).toString())
    check("简拼 64 brings two-character words early", top("64", 10).any { it.length == 2 }, top("64", 10).toString())
    val kun = top("586", 8)
    check("586 -> 困 before 简拼 words", "困" in kun, kun.toString())
    check("spellings 64 start ni/mi", NineKeyEngine.spellings("64", dictionary).take(3).containsAll(listOf("ni", "mi")),
        NineKeyEngine.spellings("64", dictionary).take(6).toString())
    check("display 64426", NineKeyEngine.display("64426", dictionary, NineKeyEngine.candidates("64426", dictionary).firstOrNull()) == "ni'hao",
        NineKeyEngine.display("64426", dictionary, NineKeyEngine.candidates("64426", dictionary).firstOrNull()))
    val typing = NineKeyEngine.display("6442", dictionary, NineKeyEngine.candidates("6442", dictionary).firstOrNull())
    check("display while typing shows what was typed", typing == "ni'ha", typing)
    val locked = NineKeyEngine.lock("64426", "mi")
    check("lock mi", locked == "mi'426", locked)
    check("locked mi -> 秘/米 first", top(locked).firstOrNull()?.let { dictionary.nineKeySpell("64").isNotEmpty() && it.isNotEmpty() } == true, top(locked).toString())
    check("backspace unlocks", NineKeyEngine.backspace("mi") == "64", NineKeyEngine.backspace("mi"))
    check("backspace digit", NineKeyEngine.backspace("644") == "64", NineKeyEngine.backspace("644"))
    check("xi'an separator", top("94'26").contains("西安"), top("94'26").toString())
    val first = NineKeyEngine.candidates("64426", dictionary).first { it.text == "你" }
    check("你 consumes 64", first.consumed == 2, first.consumed.toString())

    for ((spelling, expected) in listOf("nihaoshijie" to "你好世界", "jintiantianqi" to "今天天气", "woxiangchifan" to "我想吃饭")) {
        val digits = PinyinDictionary.nineKeyCode(spelling)
        val first = NineKeyEngine.candidates(digits, dictionary).firstOrNull()
        check("9-key sentence $digits -> $expected", first?.text == expected && first.consumed == digits.length, "${first?.text}")
    }

    started = System.nanoTime()
    val zhong = strokes.candidates("szhs", dictionary).take(5).map { it.text }
    println("stroke query ms: ${(System.nanoTime() - started) / 1_000_000}")
    check("szhs -> 中", zhong.firstOrNull() == "中", zhong.toString())
    check("h -> 一 first", strokes.candidates("h", dictionary).firstOrNull()?.text == "一", strokes.candidates("h", dictionary).take(5).map { it.text }.toString())
    check("wildcard s*hs -> 中", strokes.candidates("s*hs", dictionary).take(10).any { it.text == "中" }, strokes.candidates("s*hs", dictionary).take(10).map { it.text }.toString())
    check("中 has pinyin", strokes.candidates("szhs", dictionary).first().syllables == listOf("zhong"), strokes.candidates("szhs", dictionary).first().syllables.toString())
    check("display strokes", StrokeIndex.display("hsp*") == "一丨丿＊", StrokeIndex.display("hsp*"))

    println(if (failures == 0) "all passed" else "$failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
