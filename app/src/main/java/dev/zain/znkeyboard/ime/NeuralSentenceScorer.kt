package dev.zain.znkeyboard.ime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import dev.zain.znkeyboard.pinyin.PinyinEngine
import java.io.FileInputStream
import java.nio.LongBuffer
import java.nio.channels.FileChannel

/**
 * The character model (assets/pinyin/charlm.onnx, from tools/pinyin/charlm) as a [PinyinEngine]
 * sentence scorer: ln P of each reading as a clause, characters outside its vocabulary counting
 * as unknown. The model is memory-mapped from the APK; [load] takes a moment, so call it off the
 * main thread. Experimental, behind a setting.
 */
class NeuralSentenceScorer private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val ids: Map<Char, Long>,
) : PinyinEngine.SentenceScorer {
    // Calls and milliseconds since the last [takeStats], for the setting's summary.
    private var calls = 0
    private var totalMillis = 0.0

    /** The calls and milliseconds since the last time, then starts counting again. */
    fun takeStats(): Pair<Int, Double> = (calls to totalMillis).also {
        calls = 0
        totalMillis = 0.0
    }

    override fun score(texts: List<String>): FloatArray? {
        if (texts.isEmpty()) return null
        val started = SystemClock.elapsedRealtimeNanos()
        val lengths = LongArray(texts.size) { minOf(texts[it].length, MAX_CHARS).toLong() }
        val width = (lengths.max() + 1).toInt()
        val flat = LongArray(texts.size * width)
        texts.forEachIndexed { row, text ->
            flat[row * width] = BOUNDARY
            for (at in 0 until lengths[row].toInt()) flat[row * width + 1 + at] = ids[text[at]] ?: UNKNOWN
        }
        val result = runCatching {
            OnnxTensor.createTensor(environment, LongBuffer.wrap(flat), longArrayOf(texts.size.toLong(), width.toLong())).use { idTensor ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(lengths), longArrayOf(texts.size.toLong())).use { lengthTensor ->
                    session.run(mapOf("ids" to idTensor, "lengths" to lengthTensor)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        (output[0].value as FloatArray).copyOf()
                    }
                }
            }
        }.getOrNull()
        totalMillis += (SystemClock.elapsedRealtimeNanos() - started) / 1e6
        calls++
        return result
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val MODEL = "pinyin/charlm.onnx"
        private const val VOCAB = "pinyin/charlm_vocab.txt"
        private const val BOUNDARY = 1L
        private const val UNKNOWN = 2L
        /** The model reads 64 positions: the boundary and up to 63 characters. */
        private const val MAX_CHARS = 63
        private const val THREADS = 2

        /** The scorer, or null when the model or ONNX Runtime can't be loaded on this device. */
        fun load(context: Context): NeuralSentenceScorer? = runCatching {
            val ids = HashMap<Char, Long>(8192)
            context.assets.open(VOCAB).bufferedReader().useLines { lines ->
                lines.forEachIndexed { line, text -> text.firstOrNull()?.let { ids[it] = line + 3L } }
            }
            val model = context.assets.openFd(MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
                }
            }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            NeuralSentenceScorer(environment, environment.createSession(model, options), ids)
        }.getOrNull()
    }
}
