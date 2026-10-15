package dev.zain.znkeyboard.typing

import android.content.Context
import android.os.SystemClock
import dev.zain.znkeyboard.KeyboardSettings
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the personal touch models (one per orientation, because thumb posture differs), the
 * character language model, and their on-device persistence. Everything is main-thread only except
 * file I/O, which runs on a private single-thread executor with snapshots.
 *
 * Data lives in [Context.getNoBackupFilesDir]: it is excluded from cloud backup and device transfer,
 * and leaves the device only inside ZnKeyboard's password-encrypted settings export.
 */
class AdaptiveTypingEngine(context: Context) {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zn-typing-io").apply { priority = Thread.MIN_PRIORITY }
    }
    private val portraitModel = TouchModel()
    private val landscapeModel = TouchModel()
    private var landscape = false
    private var savedConfirmations = 0L
    private var loadedGeneration = -1

    val languageModel = CharLanguageModel()
    val decoder = TouchDecoder(::activeModel, languageModel)
    val learner = TypingLearner(::activeModel, languageModel, SystemClock::uptimeMillis)

    /** User preference; when false the keyboard falls back to plain geometric hit-testing. */
    var adaptiveEnabled = true

    /** Per-field: false in password fields and fields that opt out of personalized learning. */
    var personalizationAllowed = true
        set(value) {
            field = value
            learner.learningEnabled = value
        }

    fun setLandscape(isLandscape: Boolean) {
        if (landscape != isLandscape) {
            learner.flush()
            landscape = isLandscape
        }
    }

    fun load(generation: Int) {
        loadedGeneration = generation
        io.execute {
            val prior = runCatching {
                appContext.assets.open(PRIOR_ASSET).use { it.readBytes() }
            }.getOrNull()
            val portrait = readFile(PORTRAIT_FILE)?.let(TouchModel::decode)
            val landscapeLoaded = readFile(LANDSCAPE_FILE)?.let(TouchModel::decode)
            val personalLm = readFile(LANGUAGE_FILE)?.let { bytes ->
                CharLanguageModel().takeIf { it.decodePersonal(bytes) }
            }
            mainThread {
                languageModel.setPrior(prior)
                if (generation != loadedGeneration) return@mainThread
                // Merge rather than overwrite so taps learned before the load finished are kept.
                portrait?.let { mergeInto(portraitModel, it) }
                landscapeLoaded?.let { mergeInto(landscapeModel, it) }
                personalLm?.copyPersonalInto(languageModel)
            }
        }
    }

    /** Called when settings reset or restore the learned data in another process component. */
    fun resetIfGenerationChanged(generation: Int) {
        if (generation == loadedGeneration) return
        learner.discardPending()
        portraitModel.reset()
        landscapeModel.reset()
        languageModel.reset()
        savedConfirmations = learner.confirmations
        load(generation)
    }

    fun save() {
        // Settings may have reset learning while the keyboard was open; never write stale data back.
        val currentGeneration = KeyboardSettings.readTypingModelGeneration(appContext)
        if (currentGeneration != loadedGeneration) {
            resetIfGenerationChanged(currentGeneration)
            return
        }
        learner.flush()
        if (learner.confirmations == savedConfirmations) return
        savedConfirmations = learner.confirmations
        val portrait = portraitModel.encode()
        val landscapeBytes = landscapeModel.encode()
        val language = languageModel.encodePersonal()
        val generation = loadedGeneration
        io.execute {
            if (generation != loadedGeneration) return@execute
            writeFile(PORTRAIT_FILE, portrait)
            writeFile(LANDSCAPE_FILE, landscapeBytes)
            writeFile(LANGUAGE_FILE, language)
        }
    }

    fun shutdown() {
        save()
        io.shutdown()
    }

    private fun activeModel(): TouchModel = if (landscape) landscapeModel else portraitModel

    private fun mergeInto(target: TouchModel, loaded: TouchModel) {
        if (target.sampleCount == 0f) target.copyFrom(loaded)
    }

    private fun readFile(name: String): ByteArray? = readFile(appContext, name)

    private fun writeFile(name: String, bytes: ByteArray) = writeFile(appContext, name, bytes)

    private fun mainThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    companion object {
        private const val PRIOR_ASSET = "typing/en_char_trigram.bin"
        private const val PORTRAIT_FILE = "touch_portrait.bin"
        private const val LANDSCAPE_FILE = "touch_landscape.bin"
        private const val LANGUAGE_FILE = "letters_personal.bin"

        private fun directory(context: Context) = File(context.noBackupFilesDir, "typing")

        private fun readFile(context: Context, name: String): ByteArray? = runCatching {
            File(directory(context), name).takeIf(File::isFile)?.readBytes()
        }.getOrNull()

        private fun writeFile(context: Context, name: String, bytes: ByteArray) {
            runCatching {
                val dir = directory(context).apply { mkdirs() }
                val temp = File(dir, "$name.tmp")
                temp.writeBytes(bytes)
                temp.renameTo(File(dir, name))
            }
        }

        fun readLearnedData(context: Context) = LearnedData(
            portraitTouch = readFile(context, PORTRAIT_FILE),
            landscapeTouch = readFile(context, LANDSCAPE_FILE),
            personalLetters = readFile(context, LANGUAGE_FILE),
        )

        /** Replaces saved learning with [data], dropping parts that fail to decode; callers bump the model generation. */
        fun replaceLearnedData(context: Context, data: LearnedData) {
            deleteLearnedData(context)
            data.portraitTouch?.takeIf { TouchModel.decode(it) != null }
                ?.let { writeFile(context, PORTRAIT_FILE, it) }
            data.landscapeTouch?.takeIf { TouchModel.decode(it) != null }
                ?.let { writeFile(context, LANDSCAPE_FILE, it) }
            data.personalLetters?.takeIf { CharLanguageModel().decodePersonal(it) }
                ?.let { writeFile(context, LANGUAGE_FILE, it) }
        }

        /** Settings-side read of the learned portrait model for visualisation. */
        fun readPortraitModel(context: Context): TouchModel? = runCatching {
            File(directory(context), PORTRAIT_FILE).takeIf(File::isFile)?.readBytes()?.let(TouchModel::decode)
        }.getOrNull()

        fun deleteLearnedData(context: Context) {
            directory(context).listFiles()?.forEach(File::delete)
        }
    }

    class LearnedData(
        val portraitTouch: ByteArray?,
        val landscapeTouch: ByteArray?,
        val personalLetters: ByteArray?,
    )
}

