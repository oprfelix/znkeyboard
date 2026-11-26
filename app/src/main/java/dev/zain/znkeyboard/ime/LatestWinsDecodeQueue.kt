package dev.zain.znkeyboard.ime

import dev.zain.znkeyboard.pinyin.NineKeyEngine
import dev.zain.znkeyboard.pinyin.PinyinDictionary
import dev.zain.znkeyboard.pinyin.PinyinEngine
import dev.zain.znkeyboard.pinyin.StrokeIndex
import java.util.concurrent.Executors

internal data class BaseDecodeTicket(val session: Long, val lifecycle: Long, val generation: Long) {
    fun isCurrent(session: Long, lifecycle: Long, generation: Long): Boolean =
        this.session == session && this.lifecycle == lifecycle && this.generation == generation
}

/** The IME's candidate route after a keyboard-mode change. A changed mode never inherits a list. */
internal enum class CandidateRoute { FullKey, NineKeyPinyin, NineKeyStroke }

internal object CandidateInputTransition {
    fun route(previousMode: String, nextMode: String): CandidateRoute? {
        if (previousMode == nextMode) return null
        return when (nextMode) {
            "qwerty" -> CandidateRoute.FullKey
            "nine_pinyin" -> CandidateRoute.NineKeyPinyin
            "nine_stroke" -> CandidateRoute.NineKeyStroke
            else -> error("Unknown Chinese keyboard: $nextMode")
        }
    }

    /** Invalid numbers leave the current composition intact. */
    fun selectedNumber(digit: Char, candidateCount: Int): Int? =
        (digit - '1').takeIf { digit in '1'..'9' && it < candidateCount }

    /** Null means the 26-key request must be decoded from its detached snapshot. */
    fun synchronousCandidates(
        route: CandidateRoute,
        composing: String,
        dictionary: PinyinDictionary,
        strokeIndex: StrokeIndex?,
    ): List<PinyinEngine.Candidate>? = when (route) {
        CandidateRoute.FullKey -> null
        CandidateRoute.NineKeyPinyin -> NineKeyEngine.candidates(composing, dictionary)
        CandidateRoute.NineKeyStroke -> strokeIndex?.candidates(composing, dictionary).orEmpty()
    }
}

/** One running decode and at most one waiting decode. Replacing the waiting work never blocks input. */
internal class LatestWinsDecodeQueue(
    launch: ((Runnable) -> Unit)? = null,
) {
    private val executor = if (launch == null) Executors.newSingleThreadExecutor { work ->
        Thread(work, "ZnKeyboardBaseDecode").apply { isDaemon = true }
    } else null
    private val dispatch: (Runnable) -> Unit = launch ?: { work -> executor!!.execute(work) }
    private var running = false
    private var waiting: Runnable? = null
    private var closed = false

    @Synchronized fun submit(work: Runnable) {
        if (closed) return
        if (running) {
            waiting = work
        } else {
            running = true
            dispatch(Runnable { drain(work) })
        }
    }

    private fun drain(first: Runnable) {
        var work: Runnable? = first
        while (work != null) {
            try {
                work.run()
            } catch (_: RuntimeException) {
                // A failed decode cannot strand the latest waiting input behind it.
            }
            synchronized(this) {
                work = if (closed) null else waiting
                waiting = null
                if (work == null) running = false
            }
        }
    }

    @Synchronized fun close() {
        closed = true
        waiting = null
        executor?.shutdown()
    }
}
