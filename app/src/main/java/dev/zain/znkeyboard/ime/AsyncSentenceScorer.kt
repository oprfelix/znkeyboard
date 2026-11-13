package dev.zain.znkeyboard.ime

import android.os.Handler
import android.os.Looper
import dev.zain.znkeyboard.pinyin.PinyinEngine
import java.util.concurrent.Executors

/**
 * AI 整句排序 without holding up typing. The engine runs on the main thread with each keystroke;
 * running the character model there too made every key wait for it. Here [score] answers at once:
 * with the model's scores when this set of readings was scored already, otherwise with nothing
 * (the readings keep the decoder's order) while the model scores them on its own thread. Once it
 * is done, and if those are still the readings shown, [onScored] asks for the candidates again,
 * which now find the scores. Readings that typing has moved past are never scored, and nothing
 * (a commit, a space) ever waits for the model.
 */
class AsyncSentenceScorer(
    private val model: NeuralSentenceScorer,
    private val onScored: () -> Unit,
) : PinyinEngine.SentenceScorer {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZnKeyboardSentenceScorer").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    // The last few sets of readings and their scores, newest last.
    private val scored = object : LinkedHashMap<List<String>, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, FloatArray>?) = size > CACHED
    }
    @Volatile private var latest: List<String>? = null
    @Volatile private var closed = false

    override fun score(texts: List<String>): FloatArray? {
        synchronized(lock) { scored[texts] }?.let { return it }
        latest = texts
        if (!closed) worker.execute {
            // Typing moved on before the model got to these.
            if (closed || latest != texts) return@execute
            val scores = model.score(texts) ?: return@execute
            synchronized(lock) { scored[texts] = scores }
            main.post { if (!closed && latest == texts) onScored() }
        }
        return null
    }

    /** Calls and milliseconds the model spent since the last time. */
    fun takeStats(): Pair<Int, Double> = model.takeStats()

    /** Closes the model once whatever it is scoring is done. */
    fun close() {
        closed = true
        worker.execute { model.close() }
        worker.shutdown()
    }

    private companion object {
        const val CACHED = 6
    }
}
