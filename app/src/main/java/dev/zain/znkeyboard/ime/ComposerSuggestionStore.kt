package dev.zain.znkeyboard.ime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.zain.znkeyboard.KeyboardSettings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted storage of the retired "Save text" feature, kept only to migrate it into snippets. */
object ComposerSuggestionStore {
    private const val MIGRATED_TAG = "saved"
    const val MAX_STORED_HISTORY = 1_000
    const val MAX_ENTRY_CHARS = 32_000
    private const val HISTORY_FILE_NAME = "composer_suggestion_history.enc"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "znkeyboard_composer_suggestion_history"
    private const val KEYSTORE_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val JSON_ENTRIES = "entries"
    private const val JSON_TEXT = "text"
    private const val JSON_TIMESTAMP_MILLIS = "timestampMillis"
    private const val JSON_CIPHERTEXT = "ciphertext"
    private const val JSON_IV = "iv"

    @Synchronized
    fun read(context: Context): List<Entry> {
        return runCatching {
            val encrypted = readEncryptedFile(context) ?: return emptyList()
            parseEntries(decrypt(encrypted))
        }.getOrElse { emptyList() }
    }

    /**
     * One-time move of the retired "Save text" history into snippets, which now power both the
     * snippet panel and as-you-type completions. Entries that do not fit (snippet limit, or longer
     * than a snippet may be) stay in this encrypted file and are retried on a later launch, so
     * nothing the user saved is dropped.
     *
     * @return number of entries moved.
     */
    @Synchronized
    fun migrateIntoSnippets(context: Context): Int {
        val entries = read(context)
        if (entries.isEmpty()) {
            historyFile(context).delete()
            return 0
        }
        val snippets = KeyboardSettings.readTextSnippets(context)
        val existing = snippets.mapTo(mutableSetOf()) { it.text }
        val capacity = (KeyboardSettings.MAX_TEXT_SNIPPETS - snippets.size).coerceAtLeast(0)
        val added = mutableListOf<KeyboardSettings.TextSnippet>()
        val leftovers = mutableListOf<Entry>()
        // Stored newest-first; append oldest-first so the newest ends up as the most recent snippet.
        for (entry in entries.asReversed()) {
            val snippet = KeyboardSettings.textSnippetFromText(entry.text, listOf(MIGRATED_TAG))
            when {
                snippet.text.isBlank() || snippet.text in existing -> Unit
                entry.text.trim().length > KeyboardSettings.MAX_TEXT_SNIPPET_CHARS -> leftovers += entry
                added.size < capacity -> {
                    added += snippet
                    existing += snippet.text
                }
                else -> leftovers += entry
            }
        }
        if (added.isNotEmpty()) {
            KeyboardSettings.saveTextSnippets(context, snippets + added)
        }
        writeOrClear(context, leftovers.asReversed())
        return added.size
    }

    private fun historyFile(context: Context): File =
        context.noBackupFilesDir.resolve(HISTORY_FILE_NAME)

    private fun readEncryptedFile(context: Context): JSONObject? {
        return try {
            historyFile(context).inputStream().use { input ->
                JSONObject(String(input.readBytes(), Charsets.UTF_8))
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun write(context: Context, entries: List<Entry>) {
        val plainText = JSONObject()
            .put(JSON_ENTRIES, JSONArray().apply {
                entries.take(MAX_STORED_HISTORY).forEach { entry ->
                    put(JSONObject()
                        .put(JSON_TEXT, entry.text)
                        .put(JSON_TIMESTAMP_MILLIS, entry.timestampMillis))
                }
            })
            .toString()
        val encrypted = encrypt(plainText)
        val historyFile = historyFile(context)
        val parent = historyFile.parentFile
        parent?.mkdirs()
        val tempFile = File(parent, "${historyFile.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(encrypted.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)
        if (!tempFile.renameTo(historyFile)) {
            tempFile.copyTo(historyFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun writeOrClear(context: Context, entries: List<Entry>) {
        if (entries.isEmpty()) {
            historyFile(context).delete()
        } else {
            write(context, entries)
        }
    }

    private fun parseEntries(plainText: String): List<Entry> {
        return try {
            val entries = JSONObject(plainText).optJSONArray(JSON_ENTRIES) ?: return emptyList()
            val seen = mutableSetOf<String>()
            buildList {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val text = item.optString(JSON_TEXT).takeIf { it.isNotBlank() } ?: continue
                    if (text.length > MAX_ENTRY_CHARS || !seen.add(text)) continue
                    add(Entry(
                        text = text,
                        timestampMillis = item.optLong(JSON_TIMESTAMP_MILLIS, 0L).coerceAtLeast(0L),
                    ))
                    if (size >= MAX_STORED_HISTORY) break
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun encrypt(plainText: String): JSONObject {
        val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        return JSONObject()
            .put(JSON_CIPHERTEXT, Base64.encodeToString(
                cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .put(JSON_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
    }

    private fun decrypt(encrypted: JSONObject): String {
        val cipher = Cipher.getInstance(KEYSTORE_TRANSFORMATION)
        val spec = GCMParameterSpec(
            GCM_TAG_LENGTH_BITS,
            Base64.decode(encrypted.getString(JSON_IV), Base64.NO_WRAP),
        )
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
        return String(
            cipher.doFinal(Base64.decode(encrypted.getString(JSON_CIPHERTEXT), Base64.NO_WRAP)),
            Charsets.UTF_8,
        )
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return keyGenerator.generateKey()
    }

    data class Entry(
        val text: String,
        val timestampMillis: Long,
    )
}
