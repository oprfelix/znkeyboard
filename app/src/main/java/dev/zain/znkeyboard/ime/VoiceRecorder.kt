package dev.zain.znkeyboard.ime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records one hold-to-talk clip as 16 kHz mono 16-bit PCM, kept in memory only. The caller must
 * hold RECORD_AUDIO. [onLevel] reports a 0..1 loudness on the main thread for the space-key meter;
 * [onLimitReached] fires once the clip hits [maxDurationMs].
 */
class VoiceRecorder(
    private val maxDurationMs: Long,
    private val onLevel: (level: Float, elapsedMs: Long) -> Unit,
    private val onLimitReached: () -> Unit,
) {
    class Clip(val wav: ByteArray, val durationMs: Long)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pcm = ByteArrayOutputStream()
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var startedAt = 0L

    /** False when the microphone can't be opened (busy, or permission revoked). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE),
            )
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        runCatching { record.startRecording() }.onFailure {
            record.release()
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return false
        }

        audioRecord = record
        running = true
        startedAt = SystemClock.elapsedRealtime()
        thread = Thread({ readLoop(record) }, "ZnKeyboardVoice").apply { start() }
        return true
    }

    /** Stops recording and returns the clip with leading and trailing silence trimmed. */
    fun stop(): Clip {
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        running = false
        thread?.join(READ_JOIN_TIMEOUT_MS)
        thread = null
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null
        val samples = synchronized(pcm) { pcm.toByteArray() }
        pcm.reset()
        return Clip(wav = toWav(trimSilence(samples)), durationMs = durationMs)
    }

    fun cancel() {
        stop()
    }

    private fun readLoop(record: AudioRecord) {
        val buffer = ByteArray(SAMPLE_RATE / 10 * BYTES_PER_SAMPLE)
        var limitReported = false
        while (running) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue
            synchronized(pcm) { pcm.write(buffer, 0, read) }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val level = rmsLevel(buffer, read)
            mainHandler.post { if (running) onLevel(level, elapsed) }
            if (!limitReported && elapsed >= maxDurationMs) {
                limitReported = true
                mainHandler.post { if (running) onLimitReached() }
            }
        }
    }

    private fun rmsLevel(buffer: ByteArray, length: Int): Float {
        val shorts = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (!shorts.hasRemaining()) return 0f
        var sum = 0.0
        val count = shorts.remaining()
        while (shorts.hasRemaining()) {
            val sample = shorts.get().toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        // Speech sits roughly between 300 and 6000 RMS; map that range onto the meter.
        return ((rms - 300.0) / 5700.0).coerceIn(0.0, 1.0).toFloat()
    }

    /** Drops quiet audio before the first and after the last loud frame, keeping a short pad. */
    private fun trimSilence(samples: ByteArray): ByteArray {
        val frameBytes = SAMPLE_RATE / 50 * BYTES_PER_SAMPLE
        val frames = samples.size / frameBytes
        if (frames == 0) return samples
        fun loud(frame: Int): Boolean {
            var peak = 0
            var index = frame * frameBytes
            val end = index + frameBytes
            while (index + 1 < end) {
                val sample = (samples[index].toInt() and 0xFF) or (samples[index + 1].toInt() shl 8)
                peak = max(peak, abs(sample.toShort().toInt()))
                index += BYTES_PER_SAMPLE
            }
            return peak > SILENCE_PEAK
        }
        val first = (0 until frames).firstOrNull(::loud) ?: return ByteArray(0)
        val last = (frames - 1 downTo 0).first(::loud)
        val start = max(0, first - PAD_FRAMES) * frameBytes
        val end = min(frames, last + 1 + PAD_FRAMES) * frameBytes
        return samples.copyOfRange(start, end)
    }

    private fun toWav(samples: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + samples.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(samples.size)
        }
        return header.array() + samples
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_BYTES = 44
        private const val READ_JOIN_TIMEOUT_MS = 500L
        private const val SILENCE_PEAK = 900
        // 20 ms frames: keep 0.3 s around the speech so word edges aren't clipped.
        private const val PAD_FRAMES = 15
    }
}
