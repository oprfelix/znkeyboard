package dev.zain.znkeyboard.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.zain.znkeyboard.pinyin.PinyinEngine
import java.util.concurrent.Executors

/** Scores frozen first-decode results on one model owner thread. */
class AsyncSentenceScorer(private val model: NeuralSentenceScorer) {
    data class Performance(
        val pendingPeak: Int, val replaced: Long, val staleSkipped: Long, val inferred: Long,
        val waitP50Ms: Double, val waitP95Ms: Double, val waitP99Ms: Double,
        val inferenceP50Ms: Double, val inferenceP95Ms: Double, val inferenceP99Ms: Double,
    )

    private data class Request(
        val identity: PinyinEngine.AiSnapshotIdentity,
        val snapshot: PinyinEngine.PreparedCandidates,
        val callback: (List<PinyinEngine.Candidate>) -> Unit,
        val enqueuedAt: Long,
        val serial: Long,
    )

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ZnKeyboardSentenceScorer").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val scored = object : LinkedHashMap<List<String>, FloatArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<String>, FloatArray>?) = size > CACHED
    }
    private var pending: Request? = null
    private var running = false
    private var serial = 0L
    private var current = 0L
    private var currentIdentity: PinyinEngine.AiSnapshotIdentity? = null
    private var closed = false
    private var pendingPeak = 0
    private var replaced = 0L
    private var staleSkipped = 0L
    private var inferred = 0L
    private val waits = LongArray(SAMPLES)
    private val inferences = LongArray(SAMPLES)
    private var waitCount = 0L
    private var inferenceCount = 0L

    /** A copy safe for the base worker to use without waiting for inference. */
    fun cachedScores(texts: List<String>): FloatArray? = synchronized(lock) { scored[texts]?.copyOf() }

    /** Revokes pending and posted results; an already running ONNX call finishes on its owner thread. */
    fun revoke() = synchronized(lock) {
        if (pending != null) staleSkipped++
        pending = null
        current = ++serial
        currentIdentity = null
    }

    /** Replaces the sole pending request without growing the executor's queue. */
    fun request(
        identity: PinyinEngine.AiSnapshotIdentity,
        snapshot: PinyinEngine.PreparedCandidates,
        onCandidates: (List<PinyinEngine.Candidate>) -> Unit,
    ) {
        if (!snapshot.acceptsScores) return
        synchronized(lock) {
            if (closed) return
            val request = Request(identity, snapshot, onCandidates, SystemClock.elapsedRealtimeNanos(), ++serial)
            current = request.serial
            currentIdentity = identity
            if (running) {
                if (pending != null) replaced++
                pending = request
                pendingPeak = 1
            } else {
                running = true
                worker.execute { drain(request) }
            }
        }
    }

    private fun drain(first: Request) {
        var request: Request? = first
        while (request != null) {
            val work = request
            val valid = synchronized(lock) { !closed && current == work.serial && currentIdentity == work.identity }
            if (valid) {
                val started = SystemClock.elapsedRealtimeNanos()
                synchronized(lock) { waits[(waitCount++ % SAMPLES).toInt()] = started - work.enqueuedAt }
                val texts = work.snapshot.texts
                val cached = cachedScores(texts)
                val scores = if (cached != null) cached else if (synchronized(lock) {
                    closed || current != work.serial || currentIdentity != work.identity
                }) null else {
                    val result = model.score(texts)
                    val elapsed = SystemClock.elapsedRealtimeNanos() - started
                    synchronized(lock) {
                        inferred++
                        inferences[(inferenceCount++ % SAMPLES).toInt()] = elapsed
                        if (result != null) scored[texts] = result.copyOf()
                    }
                    result
                }
                if (scores != null && synchronized(lock) { !closed && current == work.serial && currentIdentity == work.identity }) {
                    val candidates = work.snapshot.candidates(scores)
                    main.post {
                        if (synchronized(lock) { !closed && current == work.serial && currentIdentity == work.identity }) work.callback(candidates)
                    }
                }
            } else synchronized(lock) { staleSkipped++ }
            request = synchronized(lock) {
                val next = if (closed) null else pending
                pending = null
                if (next == null) running = false
                next
            }
        }
    }

    fun takeStats(): Pair<Int, Double> = model.takeStats()

    /** Bounded recent samples; counters cover the lifetime of this scorer. */
    fun performance(): Performance {
        val snapshot = synchronized(lock) {
            Triple(
                longArrayOf(pendingPeak.toLong(), replaced, staleSkipped, inferred),
                waits.copyOf(minOf(waitCount, SAMPLES.toLong()).toInt()),
                inferences.copyOf(minOf(inferenceCount, SAMPLES.toLong()).toInt()),
            )
        }
        val wait = snapshot.second.sorted()
        val inference = snapshot.third.sorted()
        fun percentile(samples: List<Long>, percent: Int): Double =
            if (samples.isEmpty()) 0.0 else samples[(samples.size - 1) * percent / 100] / 1e6
        return Performance(snapshot.first[0].toInt(), snapshot.first[1], snapshot.first[2], snapshot.first[3],
            percentile(wait, 50), percentile(wait, 95), percentile(wait, 99),
            percentile(inference, 50), percentile(inference, 95), percentile(inference, 99))
    }

    /** Model closure is queued after the sole owner drain completes. */
    fun close(onClosed: (() -> Unit)? = null) {
      synchronized(lock) {
        if (closed) return
        closed = true
        if (pending != null) staleSkipped++
        pending = null
        current = ++serial
        currentIdentity = null
        worker.execute {
            model.close()
            val perf = performance()
            if (perf.inferred > 0 || perf.replaced > 0 || perf.staleSkipped > 0) {
                Log.i("ZnKeyboardAI", "AI performance (scorer lifetime): $perf")
            }
            if (onClosed != null) main.post { onClosed() }
        }
        worker.shutdown()
      }
    }

    private companion object {
        const val CACHED = 6
        const val SAMPLES = 256
    }
}
