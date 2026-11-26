package android.os

import java.util.concurrent.ConcurrentLinkedQueue

class Looper {
    companion object { fun getMainLooper() = Looper() }
}

class Handler(@Suppress("UNUSED_PARAMETER") looper: Looper) {
    fun post(work: Runnable): Boolean { callbacks.add(work); return true }
    companion object {
        private val callbacks = ConcurrentLinkedQueue<Runnable>()
        fun queued(): Int = callbacks.size
        fun deliver() { while (true) (callbacks.poll() ?: return).run() }
    }
}

object SystemClock { fun elapsedRealtimeNanos(): Long = System.nanoTime() }
