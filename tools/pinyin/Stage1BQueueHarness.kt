import dev.zain.znkeyboard.ime.BaseDecodeTicket
import dev.zain.znkeyboard.ime.CandidateInputTransition
import dev.zain.znkeyboard.ime.CandidateRoute
import dev.zain.znkeyboard.ime.LatestWinsDecodeQueue
import dev.zain.znkeyboard.pinyin.NineKeyEngine
import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.PinyinTapBuffer
import java.io.File

private class ComposerHarness {
    var session = 1L
    var lifecycle = 1L
    var generation = 0L
    var visible = ""
    var composing = ""
    val computed = mutableListOf<String>()
    val deliveries = mutableListOf<() -> Unit>()
    var worker: Runnable? = null
    val queue = LatestWinsDecodeQueue { worker = it }

    fun type(value: String) {
        composing = value
        visible = ""
        val ticket = BaseDecodeTicket(session, lifecycle, ++generation)
        queue.submit(Runnable {
            computed += value
            deliveries += {
                if (ticket.isCurrent(session, lifecycle, generation) && composing == value) visible = value
            }
        })
    }

    fun drain() { worker!!.run() }
    fun deliver() { deliveries.forEach { it() }; deliveries.clear() }
}

/** Exercises the production mode router and real decoders with the IME's generation/apply rules. */
private class ModeHarness(private val dictionary: PinyinDictionary, var composing: String) {
    var mode = "qwerty"
    var generation = 1L
    var candidateGeneration = 1L
    var candidates = PinyinEngine.candidates(composing, dictionary)
    var committed: String? = null
    var worker: Runnable? = null
    val deliveries = mutableListOf<() -> Unit>()
    private val queue = LatestWinsDecodeQueue { worker = it }

    fun switch(next: String) {
        val route = CandidateInputTransition.route(mode, next) ?: return
        generation++
        candidateGeneration = -1
        candidates = emptyList()
        mode = next
        val synchronous = CandidateInputTransition.synchronousCandidates(route, composing, dictionary, null)
        if (synchronous == null) schedule() else {
            candidates = synchronous
            candidateGeneration = generation
        }
    }

    private fun schedule() {
        val ticket = BaseDecodeTicket(1, 1, ++generation)
        val text = composing
        val view = dictionary.snapshotView()
        queue.submit(Runnable {
            val decoded = PinyinEngine.candidates(text, view)
            deliveries += {
                if (ticket.isCurrent(1, 1, generation) && mode == "qwerty" && composing == text) {
                    candidates = decoded
                    candidateGeneration = generation
                }
            }
        })
    }

    fun type(text: String) {
        composing = text
        candidates = emptyList()
        candidateGeneration = -1
        if (mode == "qwerty") schedule() else {
            val route = CandidateInputTransition.route("qwerty", mode)!!
            candidates = CandidateInputTransition.synchronousCandidates(route, text, dictionary, null)!!
            candidateGeneration = ++generation
        }
    }

    fun deliver() { worker?.run(); deliveries.forEach { it() }; deliveries.clear() }

    fun settle() {
        if (mode == "qwerty" && candidateGeneration != generation) {
            candidates = PinyinEngine.candidates(composing, dictionary.snapshotView())
            candidateGeneration = generation
        }
    }

    fun number(digit: Char) {
        settle()
        CandidateInputTransition.selectedNumber(digit, candidates.size)?.let { choose(it) }
    }

    fun space() { settle(); choose(0) }
    fun punctuation() { settle(); choose(0) }
    fun tap(index: Int) { if (candidateGeneration == generation) choose(index) }

    private fun choose(index: Int) {
        val candidate = candidates.getOrNull(index) ?: return
        committed = candidate.text
        composing = ""
        candidates = emptyList()
        candidateGeneration = -1
        generation++
    }
}

fun main() {
    ComposerHarness().apply {
        type("n"); type("ni"); type("nih"); type("nihao")
        drain(); deliver()
        check(computed == listOf("n", "nihao")) { "waiting work was not latest-wins: $computed" }
        check(visible == "nihao")
    }
    ComposerHarness().apply {
        type("nihao"); type("niha"); drain(); deliver()
        check(visible == "niha") { "delete resurrected an older candidate" }
    }
    ComposerHarness().apply {
        type("old"); session++; lifecycle++; type("new"); drain(); deliver()
        check(visible == "new") { "old session overwrote the new field" }
    }
    ComposerHarness().apply {
        type("old"); lifecycle++; drain(); deliver()
        check(visible.isEmpty()) { "old input view delivered after replacement" }
    }
    ComposerHarness().apply {
        type("ni"); composing = ""; generation++; drain(); deliver()
        check(visible.isEmpty()) { "decode delivered after commit" }
    }
    ComposerHarness().apply {
        type("ni"); queue.close(); drain(); deliver()
        check(visible == "ni")
        check(computed == listOf("ni"))
    }
    val dictionary = File("app/src/main/assets/pinyin/dict.tsv").bufferedReader().use { PinyinDictionary.load(it, emptySet()) }
    ModeHarness(dictionary, "ni").apply {
        val full = candidates.map { it.text to it.consumed }
        switch("nine_pinyin")
        check(candidates.map { it.text to it.consumed } == NineKeyEngine.candidates("ni", dictionary).map { it.text to it.consumed })
        switch("qwerty")
        check(candidates.isEmpty() && candidateGeneration != generation)
        space()
        check(committed == full.first().first) { "space used a nine-key candidate after returning to 26-key" }
        deliver()
        check(committed == full.first().first && candidates.isEmpty())
    }
    ModeHarness(dictionary, "64").apply {
        switch("nine_pinyin")
        val nine = candidates.toList()
        check(nine.isNotEmpty())
        switch("qwerty")
        check(candidates.isEmpty())
        settle()
        check(candidates == PinyinEngine.candidates("64", dictionary))
        check(candidates != nine) { "nine-key list crossed into 26-key generation" }
    }
    ModeHarness(dictionary, "ni").apply {
        switch("nine_pinyin"); switch("qwerty"); switch("nine_stroke"); switch("qwerty")
        check(candidates.isEmpty())
        deliver()
        check(candidates == PinyinEngine.candidates("ni", dictionary))
    }
    val sparse = PinyinDictionary.load("".reader().buffered(), emptySet())
    ModeHarness(sparse, "ni").apply {
        type("nih")
        number('9')
        check(composing == "nih" && committed == null) { "out-of-range number committed while pending" }
        deliver()
        number('9')
        check(composing == "nih" && committed == null) { "out-of-range number committed after delivery" }
    }
    ModeHarness(dictionary, "ni").apply {
        check(candidates.size >= 2)
        number('2')
        check(committed == PinyinEngine.candidates("ni", dictionary)[1].text)
    }
    ModeHarness(dictionary, "ni").apply {
        type("nih")
        number('2')
        check(committed == PinyinEngine.candidates("nih", dictionary)[1].text)
        deliver()
        check(candidates.isEmpty())
    }
    ModeHarness(dictionary, "ni").apply {
        type("nih"); type("ni") // deletion supersedes the pending longer input
        tap(0)
        check(committed == null)
        punctuation()
        check(committed == PinyinEngine.candidates("ni", dictionary).first().text)
        deliver()
        check(candidates.isEmpty())
    }
    val taps = PinyinTapBuffer().apply { follow("", "n", null); follow("n", "ni", null) }
    val detachedDictionary = dictionary.snapshotView()
    val detachedTaps = taps.snapshot()
    var detachedResult: List<PinyinEngine.Candidate>? = null
    val detachedQueue = LatestWinsDecodeQueue { it.run() }
    detachedQueue.submit(Runnable { detachedResult = PinyinEngine.candidates("ni", detachedDictionary, detachedTaps) })
    check(detachedResult!!.map { it.text to it.consumed } ==
        PinyinEngine.candidates("ni", dictionary, taps).map { it.text to it.consumed })
    taps.follow("ni", "nih", null)
    check(detachedResult!!.isNotEmpty())
    repeat(50) { PinyinEngine.candidates("ni", dictionary, taps) }
    val direct = LongArray(300) {
        val start = System.nanoTime()
        PinyinEngine.candidates("ni", dictionary, taps)
        System.nanoTime() - start
    }
    var scheduled: Runnable? = null
    val benchQueue = LatestWinsDecodeQueue { scheduled = it }
    val enqueue = LongArray(300) {
        val start = System.nanoTime()
        val view = dictionary.snapshotView()
        val tapView = taps.snapshot()
        benchQueue.submit(Runnable { PinyinEngine.candidates("ni", view, tapView) })
        System.nanoTime() - start
    }
    check(scheduled != null)
    fun p95(values: LongArray) = values.sorted()[(values.size - 1) * 95 / 100]
    println("desktop direct decode P95 ns=${p95(direct)}; snapshot plus latest-wins submit P95 ns=${p95(enqueue)} (worker execution excluded)")
    ModeHarness(dictionary, "ni").apply {
        type("nih")
        deliver()
        tap(0)
        check(committed == PinyinEngine.candidates("nih", dictionary).first().text)
    }
    println("Stage 1B queue, identity, detached decode and IME candidate-transition checks: 15 ok")
}
