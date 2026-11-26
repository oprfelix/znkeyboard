package dev.zain.znkeyboard.ime

import android.os.Handler
import android.os.Looper
import dev.zain.znkeyboard.pinyin.PinyinEngine
import java.util.concurrent.Executors

/** Scores a frozen first-decode result on the existing serial model worker. */
class AsyncSentenceScorer(private val model: NeuralSentenceScorer) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZnKeyboardSentenceScorer").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val scored = object : LinkedHashMap<List<String>, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, FloatArray>?) = size > CACHED
    }
    @Volatile private var latest: List<String>? = null
    @Volatile private var closed = false

    /** A copy safe for the base worker to use without waiting for inference. */
    fun cachedScores(texts: List<String>): FloatArray? = synchronized(lock) { scored[texts]?.copyOf() }

    /** The callback receives candidates reconstructed from this exact immutable decode snapshot. */
    fun request(snapshot: PinyinEngine.PreparedCandidates, onCandidates: (List<PinyinEngine.Candidate>) -> Unit) {
        if (!snapshot.acceptsScores || closed) return
        val texts = snapshot.texts
        latest = texts
        worker.execute {
            if (closed || latest != texts) return@execute
            val scores = cachedScores(texts) ?: model.score(texts)?.also { result ->
                synchronized(lock) { scored[texts] = result.copyOf() }
            } ?: return@execute
            val candidates = snapshot.candidates(scores)
            main.post { if (!closed && latest == texts) onCandidates(candidates) }
        }
    }

    fun takeStats(): Pair<Int, Double> = model.takeStats()

    /** The model session closes after work already running on its owner queue finishes. */
    fun close() {
        closed = true
        worker.execute { model.close() }
        worker.shutdown()
    }

    private companion object {
        const val CACHED = 6
    }
}
