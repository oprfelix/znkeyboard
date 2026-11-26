import android.os.Handler
import dev.zain.znkeyboard.ime.AsyncSentenceScorer
import dev.zain.znkeyboard.ime.NeuralSentenceScorer
import dev.zain.znkeyboard.pinyin.PinyinEngine
import java.util.concurrent.TimeUnit

private fun identity(session: Long, generation: Long = 1) =
    PinyinEngine.AiSnapshotIdentity(session, 1, generation, "same", 1)

private fun await(condition: () -> Boolean) {
    repeat(500) { if (condition()) return else Thread.sleep(2) }
    error("timed out")
}

fun main() {
    var passed = 0
    run {
        val model = NeuralSentenceScorer()
        val scorer = AsyncSentenceScorer(model)
        val received = mutableListOf<String>()
        fun submit(text: String, id: PinyinEngine.AiSnapshotIdentity = identity(1)) =
            scorer.request(id, PinyinEngine.PreparedCandidates(listOf(text))) { received += it.single().text }
        submit("running")
        check(model.entered.await(5, TimeUnit.SECONDS)); passed++
        submit("old-pending")
        submit("new-pending")
        val during = scorer.performance()
        check(during.pendingPeak == 1 && during.replaced == 1L && during.inferred == 0L); passed++
        model.release.countDown()
        await { model.calls.get() == 2 }
        await { scorer.performance().inferred == 2L }
        Handler.deliver()
        check(received == listOf("new-pending:11.0")); passed++
        check(model.peak.get() == 1 && scorer.performance().staleSkipped == 0L); passed++
        println("controlled queue stats: ${scorer.performance()}")
        scorer.close()
        check(model.closed.await(5, TimeUnit.SECONDS)); passed++
    }
    run {
        val model = NeuralSentenceScorer()
        val scorer = AsyncSentenceScorer(model)
        val received = mutableListOf<String>()
        scorer.request(identity(1), PinyinEngine.PreparedCandidates(listOf("same"))) { received += "old" }
        check(model.entered.await(5, TimeUnit.SECONDS)); passed++
        scorer.request(identity(2), PinyinEngine.PreparedCandidates(listOf("same"))) { received += "new" }
        model.release.countDown()
        await { scorer.performance().inferred == 1L }
        await { Handler.queued() == 1 }
        Handler.deliver()
        check(received == listOf("new")); passed++
        val perf = scorer.performance()
        check(perf.pendingPeak == 1 && perf.inferred == 1L && perf.waitP99Ms >= 0.0 && perf.inferenceP99Ms >= 0.0); passed++
        scorer.close()
        check(model.closed.await(5, TimeUnit.SECONDS)); passed++
    }
    run {
        val model = NeuralSentenceScorer()
        val scorer = AsyncSentenceScorer(model)
        val received = mutableListOf<String>()
        scorer.request(identity(1), PinyinEngine.PreparedCandidates(listOf("first"))) { received += "first" }
        check(model.entered.await(5, TimeUnit.SECONDS)); passed++
        scorer.request(identity(1, 2), PinyinEngine.PreparedCandidates(listOf("delete"))) { received += "delete" }
        scorer.revoke() // deletion, commit, mode switch, lifecycle transition share this operation
        model.release.countDown()
        await { scorer.performance().inferred == 1L }
        await { scorer.performance().staleSkipped == 1L }
        Handler.deliver()
        check(received.isEmpty() && model.calls.get() == 1 && scorer.performance().staleSkipped == 1L); passed++
        scorer.close()
        check(model.closed.await(5, TimeUnit.SECONDS)); passed++
    }
    run {
        val model = NeuralSentenceScorer()
        val scorer = AsyncSentenceScorer(model)
        scorer.request(identity(1), PinyinEngine.PreparedCandidates(listOf("first"))) {}
        check(model.entered.await(5, TimeUnit.SECONDS)); passed++
        scorer.request(identity(1, 2), PinyinEngine.PreparedCandidates(listOf("pending"))) {}
        var closeCallback = false
        scorer.close { closeCallback = true }
        model.release.countDown()
        check(model.closed.await(5, TimeUnit.SECONDS)); passed++
        check(!closeCallback); passed++
        await { Handler.queued() == 1 }
        Handler.deliver()
        check(closeCallback); passed++
        check(model.calls.get() == 1 && scorer.performance().staleSkipped == 1L); passed++
    }
    println("Stage 2 AI queue: $passed checks passed; running <=1, pending <=1")
}
