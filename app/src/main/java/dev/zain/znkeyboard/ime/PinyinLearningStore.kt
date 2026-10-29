package dev.zain.znkeyboard.ime

import android.content.Context
import dev.zain.znkeyboard.KeyboardSettings
import dev.zain.znkeyboard.pinyin.PinyinUserData
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device storage for learned pinyin ([PinyinUserData]). Like the typing models it lives in
 * [Context.getNoBackupFilesDir]: excluded from cloud backup and device transfer, and leaves the
 * device only inside ZnKeyboard's password-encrypted settings export.
 */
class PinyinLearningStore(context: Context) {
    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zn-pinyin-io").apply { priority = Thread.MIN_PRIORITY }
    }

    /** Encodes [data] now and writes it on the I/O thread, unless [generation] went stale. Main thread. */
    fun save(data: PinyinUserData, generation: Int) {
        val bytes = data.encode()
        io.execute {
            // Settings may have cleared or restored learning meanwhile; never write stale data back.
            if (generation == KeyboardSettings.readPinyinLearningGeneration(appContext)) write(appContext, bytes)
        }
    }

    fun shutdown() {
        io.shutdown()
    }

    companion object {
        private const val FILE_NAME = "pinyin_user.txt"

        private fun file(context: Context) = File(File(context.noBackupFilesDir, "pinyin"), FILE_NAME)

        /** Saved learning, or null when there is none. Blocking; call off the main thread. */
        fun read(context: Context): PinyinUserData? = readBytes(context)?.let(PinyinUserData::decode)

        private fun write(context: Context, bytes: ByteArray) {
            runCatching {
                val target = file(context)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "$FILE_NAME.tmp")
                temp.writeBytes(bytes)
                temp.renameTo(target)
            }
        }

        /** The saved file, for the encrypted settings export. */
        fun readBytes(context: Context): ByteArray? = runCatching {
            file(context).takeIf(File::isFile)?.readBytes()
        }.getOrNull()

        /** Replaces saved learning when [bytes] decode; callers bump the learning generation. */
        fun replace(context: Context, bytes: ByteArray) {
            if (PinyinUserData.decode(bytes) == null) return
            write(context, bytes)
        }

        fun delete(context: Context) {
            runCatching { file(context).delete() }
        }
    }
}
