package dev.zain.znkeyboard.ime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NeuralSentenceScorer {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val closed = CountDownLatch(1)
    fun score(texts: List<String>): FloatArray {
        val now = active.incrementAndGet()
        peak.updateAndGet { maxOf(it, now) }
        calls.incrementAndGet()
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        active.decrementAndGet()
        return floatArrayOf(texts.first().length.toFloat())
    }
    fun takeStats(): Pair<Int, Double> = calls.get() to 0.0
    fun close() { check(active.get() == 0); closed.countDown() }
}
