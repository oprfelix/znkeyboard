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

private fun percentile(values: List<Long>, percent: Int): Long = values.sorted()[(values.size - 1) * percent / 100]

fun main(args: Array<String>) {
    val dictionary = File(args[0]).bufferedReader().use { PinyinDictionary.load(it, PinyinFuzzyRule.DEFAULTS) }
    File(args[0]).resolveSibling("trade.tsv").bufferedReader().use(dictionary::addWords)
    val model = RandomAccessFile(args[1], "r").use { file ->
        PinyinLanguageModel.load(file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
    }
    dictionary.languageModel = checkNotNull(model)
    val scorer = PinyinEngine.SentenceScorer(::scores)
    val all = inputs(File(args[2]))
    var changed = 0
    var scored = 0
    File(args[3]).bufferedWriter().use { out ->
        all.forEachIndexed { at, input ->
            val before = if (at % 17 == 0) SentenceDecoder.Context("中国", 0) else null
            val taps = if (at % 19 == 0) SentenceDecoder.Taps { FloatArray(26) { key -> key / 37f } } else null
            val prepared = PinyinEngine.prepareCandidates(input, dictionary.snapshotView(), taps, before, aiEnabled = true)
            val base = prepared.candidates()
            val directBase = PinyinEngine.candidates(input, dictionary, taps, before, PinyinEngine.SentenceScorer { null })
            check(base == directBase) {
                val mismatch = (0 until minOf(base.size, directBase.size)).firstOrNull { base[it] != directBase[it] }
                "base mismatch at $at: $input; sizes ${base.size}/${directBase.size}; first=$mismatch; prepared=${mismatch?.let(base::get)}; direct=${mismatch?.let(directBase::get)}"
            }
            val result = prepared.candidates(scores(prepared.texts))
            val direct = PinyinEngine.candidates(input, dictionary, taps, before, scorer)
            check(result == direct) { "fixed-score mismatch at $at: $input" }
            if (prepared.acceptsScores) {
                scored++
                if (PinyinEngine.visibleOrderChanged(base, result)) changed++
            }
            out.appendLine("$at\t$input\t$result")
        }
    }
    dictionary.learn("你好", listOf("ni", "hao"), 4)
    dictionary.learn("测试词", listOf("ce", "shi", "ci"), 3)
    dictionary.learnPair("你", "好", 2)
    for (input in listOf("nihao", "ceshici", "xian", "yue", "simananzhui")) {
        val prepared = PinyinEngine.prepareCandidates(input, dictionary.snapshotView(), aiEnabled = true)
        val base = PinyinEngine.candidates(input, dictionary, scorer = PinyinEngine.SentenceScorer { null })
        val ranked = PinyinEngine.candidates(input, dictionary, scorer = scorer)
        check(prepared.candidates() == base) { "learned base mismatch: $input" }
        check(prepared.candidates(scores(prepared.texts)) == ranked) { "learned AI mismatch: $input" }
    }
    val frozen = PinyinEngine.prepareCandidates("nihao", dictionary.snapshotView(), aiEnabled = true)
    check(runCatching { (frozen.readings as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    check(runCatching { (frozen.texts as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    check(runCatching { (frozen.candidates() as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
    check(!PinyinEngine.visibleOrderChanged(frozen.candidates(), frozen.candidates()))
    val identity = PinyinEngine.AiSnapshotIdentity(1, 2, 3, "nihao", dictionary.version)
    check(identity.isCurrent(1, 2, 3, "nihao", dictionary.version))
    check(!identity.isCurrent(4, 2, 3, "nihao", dictionary.version)) // same text, new Session
    check(!identity.isCurrent(1, 4, 3, "nihao", dictionary.version)) // new Lifecycle
    check(!identity.isCurrent(1, 2, 4, "nihao", dictionary.version)) // input/delete/commit
    check(!identity.isCurrent(1, 2, 3, "niha", dictionary.version)) // composition changed
    check(!identity.isCurrent(1, 2, 3, "nihao", dictionary.version + 1)) // learning changed
    val ready = PinyinEngine.AiApplyState(1, 2, 3, "nihao", dictionary.version,
        baseDelivered = true, candidatesCurrent = true, scorerCurrent = true, qwerty = true,
        choosing = false, fixing = false, editing = false, active = true, destroyed = false)
    check(PinyinEngine.mayApplyAi(identity, ready))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(session = 4))) // same text, new session
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(lifecycle = 4)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(generation = 4))) // type/delete/commit
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(composing = "niha")))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(dictionaryVersion = dictionary.version + 1)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(baseDelivered = false)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(candidatesCurrent = false)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(scorerCurrent = false)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(qwerty = false))) // mode switch
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(choosing = true))) // candidate interaction
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(fixing = true)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(editing = true)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(active = false)))
    check(!PinyinEngine.mayApplyAi(identity, ready.copy(destroyed = true)))
    val sample = all.filterIndexed { at, _ -> at % 20 == 0 }.take(200)
    repeat(4) { round ->
        val oldTimes = ArrayList<Long>()
        val newTimes = ArrayList<Long>()
        for (input in sample) {
            val start = System.nanoTime()
            PinyinEngine.candidates(input, dictionary, scorer = PinyinEngine.SentenceScorer { null })
            PinyinEngine.candidates(input, dictionary, scorer = scorer)
            oldTimes += System.nanoTime() - start
            val next = System.nanoTime()
            val prepared = PinyinEngine.prepareCandidates(input, dictionary.snapshotView(), aiEnabled = true)
            prepared.candidates()
            prepared.candidates(scores(prepared.texts))
            newTimes += System.nanoTime() - next
        }
        if (round > 0) println("desktop CPU round=$round sample=${sample.size}; old two-decode P50/P95 ns=${percentile(oldTimes,50)}/${percentile(oldTimes,95)}; snapshot P50/P95 ns=${percentile(newTimes,50)}/${percentile(newTimes,95)}")
    }
    println("fixed-score and metadata equal: ${all.size} inputs; AI eligible=$scored; old AI refreshes=$scored; new visible refreshes=$changed; saved=${scored-changed}")
    println("snapshot immutability, identical-order refresh and AI application gate checks: 25 ok")
}
