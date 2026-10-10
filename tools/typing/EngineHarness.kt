// Verification harness for the adaptive typing engine. Run via tools/typing/verify_engine.sh.
// Mirrors the typist model in touch_simulation.py, but drives the real Kotlin classes, including
// TypingLearner's delayed confirmation and backspace-repair labelling.
import dev.zain.znkeyboard.typing.*
import java.io.File
import kotlin.random.Random
import kotlin.math.abs

val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
const val W = 411f; const val PAD = 6f; const val GAP = 6f; const val PY = 54f
val PX = (W - 2 * PAD + GAP) / 10f
val KW = PX - GAP
val cx = FloatArray(26); val cy = FloatArray(26)
fun setup() {
    ROWS.forEachIndexed { r, row ->
        val left = when (r) { 0 -> PAD; 1 -> PAD + PX / 2; else -> PAD + 1.5f * PX }
        row.forEachIndexed { i, ch -> cx[ch - 'a'] = left + i * PX + KW / 2; cy[ch - 'a'] = PY * (r + 0.5f) }
    }
}
fun rectHit(x: Float, y: Float): Int {
    val r = (y / PY).toInt(); if (y < 0 || r > 2) return -1
    val row = ROWS[r]
    for (i in row.indices) {
        val c = cx[row[i] - 'a']
        val lo = if (i == 0) -1e9f else (cx[row[i - 1] - 'a'] + c) / 2
        val hi = if (i == row.length - 1) 1e9f else (cx[row[i + 1] - 'a'] + c) / 2
        if (x >= lo && x < hi) {
            if (r == 2 && (x < cx['z' - 'a'] - PX / 2 || x > cx['m' - 'a'] + PX / 2)) return -1
            return row[i] - 'a'
        }
    }
    return -1
}
fun Random.gauss(): Float { var u = 0.0; var v = 0.0; while (u == 0.0) u = nextDouble(); v = nextDouble(); return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat() }

fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) failures++ }
var failures = 0

fun main(args: Array<String>) {
    setup()
    val prior = File(args[0]).readBytes()
    val words = File(args[1]).readLines().take(20000).map { it.split("\t") }.filter { it[0].all(Char::isLetter) }
    val wl = words.map { it[0] }; val wt = words.map { it[1].toDouble() }
    val cum = DoubleArray(wt.size); var acc = 0.0; wt.forEachIndexed { i, w -> acc += w; cum[i] = acc }

    // --- unit checks ---
    run {
        val m = TouchModel(); repeat(200) { m.learn(4, 3f, 6f, PX, PY) }
        val out = FloatArray(4); m.params(4, PX, PY, out)
        check("TouchModel learns offset toward samples", out[0] > 2f && out[1] > 4.5f)
        val back = TouchModel.decode(m.encode())!!; val out2 = FloatArray(4); back.params(4, PX, PY, out2)
        check("TouchModel encode/decode roundtrip", out.contentEquals(out2))
        val o = FloatArray(4); m.params('r' - 'a', PX, PY, o)
        check("Region sharing moves untrained neighbour 'r'", o[1] > 1f)
        val far = FloatArray(4); m.params('m' - 'a', PX, PY, far)
        check("Other region 'm' stays neutral", abs(far[1]) < 0.01f)
        repeat(500) { m.learn(4, 39f, 0f, PX, PY) }
        m.params(4, PX, PY, out); check("Mean clamped below 45% pitch", out[0] <= 0.45f * PX + 1e-3f)
    }
    run {
        val lm = CharLanguageModel(); lm.setPrior(prior)
        val before = lm.logProb('h', 'a', 'i')
        repeat(300) { "hai kya haal hai bhai ".let { s -> for (i in 2 until s.length) lm.learn(s[i - 2], s[i - 1], s[i]) } }
        check("Personal LM adapts to Hinglish (P(i|ha) rises)", lm.logProb('h', 'a', 'i') > before + 0.5f)
        val copy = CharLanguageModel(); copy.setPrior(prior); check("Personal LM persistence", copy.decodePersonal(lm.encodePersonal()) && abs(copy.logProb('h','a','i') - lm.logProb('h','a','i')) < 1e-5f)
    }
    run {
        var model = TouchModel(); val lm = CharLanguageModel(); var t = 0L
        val learner = TypingLearner({ model }, lm, { t })
        // user aimed at 'e' (index 4) but touched near 'w' and got 'w'; deletes it and types 'e'
        val sW = TypingLearner.Sample('w' - 'a', cx[22] + 14f, cy[22], cx, cy, PX, PY)
        learner.onCommitted('t', null); learner.onCommitted('h', null); learner.onCommitted('w', sW)
        t += 500; learner.onBackspace(); t += 300; learner.onCommitted('e', null)
        check("Repair relabels deleted 'w' touch as an 'e' sample", model.samplesFor(4) > 0.9f)
        check("Deleted 'w' is not confirmed as 'w'", model.samplesFor(22) == 0f)
        model = TouchModel(); learner.discardPending()
        val sA = TypingLearner.Sample(0, cx[0], cy[0], cx, cy, PX, PY)
        learner.onCommitted('a', sA); learner.onBackspace(); t += 300; learner.onCommitted('p', null)
        check("Non-neighbour replacement is an edit, not a label", model.samplesFor('p' - 'a') == 0f)
        learner.discardPending(); learner.learningEnabled = false
        learner.onCommitted('a', sA); learner.flush()
        check("No learning when personalization disallowed", model.samplesFor(0) == 0f)
    }

    // --- end-to-end: same typist model as tools/typing/touch_simulation.py ---
    val users = 30; val letters = 4000
    var baseErr = 0; var newErr = 0; var lateBase = 0; var lateNew = 0; var lateN = 0; var n = 0
    val mm = 1f / 0.15875f
    for (u in 0 until users) {
        val rng = Random(7000 + u)
        val noise = (1.0f + rng.nextFloat() * 0.5f) * mm
        val yBias = (1.0f + 0.5f * rng.gauss()) * mm
        val side = floatArrayOf(0.9f * mm * rng.gauss(), 0.9f * mm * rng.gauss())
        val offX = FloatArray(26); val offY = FloatArray(26)
        for (k in 0 until 26) {
            val r = ROWS.indexOfFirst { it.contains('a' + k) }
            val s = side[if (cx[k] < W / 2) 0 else 1]
            val reach = (cx[k] - W / 2) / (W / 2) * (0.4f + 0.2f * rng.gauss()) * mm
            offX[k] = s + reach + 0.4f * mm * rng.gauss(); offY[k] = yBias + (2 - r) * 0.15f * mm + 0.4f * mm * rng.gauss()
        }
        val driftX = 0.2f * mm * rng.gauss(); val driftY = (0.3f + 0.2f * rng.gauss()) * mm; val dsd = 0.55f * mm
        val model = TouchModel(); val lm = CharLanguageModel(); lm.setPrior(prior)
        var clock = 0L
        val learner = TypingLearner({ model }, lm, { clock })
        val decoder = TouchDecoder({ model }, lm)
        var typed = 0
        while (typed < letters) {
            val idx = cum.binarySearch(rng.nextDouble() * acc).let { if (it < 0) -it - 1 else it }
            for (ch in wl[idx.coerceAtMost(wl.size - 1)]) {
                val k = ch - 'a'
                val x = cx[k] + offX[k] + noise * rng.gauss(); val y = cy[k] + offY[k] + noise * rng.gauss()
                val ux = x + driftX + dsd * rng.gauss(); val uy = y + driftY + dsd * rng.gauss()
                val base = rectHit(ux, uy)
                val g = rectHit(x, y)
                val dec = if (g < 0) -1 else decoder.decode(x, y, g, cx, cy, PX, PY, learner.previous2(), learner.previous1(), true)
                clock += 180
                if (base != k) baseErr++; if (dec != k) newErr++; n++
                if (typed >= letters * 3 / 4) { lateN++; if (base != k) lateBase++; if (dec != k) lateNew++ }
                if (dec >= 0) {
                    learner.onCommitted('a' + dec, TypingLearner.Sample(dec, x, y, cx, cy, PX, PY))
                    if (dec != k) { clock += 400; learner.onBackspace(); clock += 300; learner.onCommitted(ch, null) }
                } else learner.onCommitted(ch, null)
                typed++
            }
            learner.onCommitted(' ', null)
        }
        learner.flush()
    }
    println("E2E baseline(release+rect) %.2f%% -> adaptive %.2f%% overall; after learning %.2f%% -> %.2f%% (%d typists x %d letters)".format(
        100.0 * baseErr / n, 100.0 * newErr / n, 100.0 * lateBase / lateN, 100.0 * lateNew / lateN, users, letters))
    check("Adaptive decoder cuts late error rate by >60%", lateNew < lateBase * 0.4)
    if (failures > 0) System.exit(1)
}

