package dev.zain.znkeyboard

import android.content.Context
import android.net.Uri
import dev.zain.znkeyboard.constants.SettingsBackupDefaults
import dev.zain.znkeyboard.constants.SettingsUiTimings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale

internal fun fetchModelOptions(
    baseUrl: String,
    apiKey: String,
): Result<List<ModelOption>> {
    return runCatching {
        val endpoint = buildModelsUrl(baseUrl)
        val data = getJsonObject(
            endpoint = endpoint,
            apiKey = apiKey,
            failureMessage = "Could not load models",
        ).getJSONArray("data")
        buildList {
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val id = model.optString("id").trim()
                if (id.isNotBlank()) {
                    add(
                        ModelOption(
                            id = id,
                            name = model.optString("name").trim().takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.distinctBy { it.id }.sortedBy { it.id.lowercase(Locale.US) }
    }
}

private fun buildModelsUrl(baseUrl: String): URL {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val endpoint = URL("$normalizedBaseUrl/models")
    if (endpoint.protocol != "https") {
        throw IOException("Use an HTTPS endpoint to load models.")
    }
    return endpoint
}

private fun getJsonObject(
    endpoint: URL,
    apiKey: String,
    failureMessage: String,
): JSONObject {
    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = SettingsUiTimings.MODEL_LOAD_TIMEOUT_MS
        readTimeout = SettingsUiTimings.MODEL_LOAD_TIMEOUT_MS
        setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
    }

    try {
        val responseCode = connection.responseCode
        val responseText = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        if (responseCode !in 200..299) {
            throw IOException("$failureMessage ($responseCode).")
        }
        return JSONObject(responseText)
    } finally {
        connection.disconnect()
    }
}

/**
 * Gemini's model list mixes in image, video, speech and embedding models; keep the ones that can
 * rewrite text, without the "models/" prefix the OpenAI-compatible endpoint adds.
 */
internal fun List<ModelOption>.geminiTextModels(): List<ModelOption> {
    return map { it.copy(id = it.id.removePrefix("models/")) }
        .filter { option ->
            val id = option.id.lowercase(Locale.US)
            id.startsWith("gemini") && GEMINI_NON_TEXT_MARKERS.none { it in id }
        }
        .distinctBy { it.id }
}

private val GEMINI_NON_TEXT_MARKERS = listOf(
    "embedding", "image", "imagen", "tts", "audio", "live", "veo", "robotics", "computer-use", "aqa",
    "transcribe", "nano-banana",
)

internal fun List<ModelOption>.filterForQuery(query: String): List<ModelOption> {
    val normalizedQuery = query.trim().lowercase(Locale.US)
    if (normalizedQuery.isBlank()) return this

    return filter { option ->
        option.id.lowercase(Locale.US).contains(normalizedQuery) ||
            option.name?.lowercase(Locale.US)?.contains(normalizedQuery) == true
    }
}

internal fun writeEncryptedSettingsBackup(
    context: Context,
    uri: Uri,
    password: CharArray,
) {
    try {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open export file.")
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(
                EncryptedBackupCodec.encrypt(
                    plainText = KeyboardSettings.createBackupPayloadJson(context),
                    password = password,
                ),
            )
        }
    } finally {
        password.fill('\u0000')
    }
}

internal fun restoreEncryptedSettingsBackup(
    context: Context,
    uri: Uri,
    password: CharArray,
): KeyboardSettings.BackupRestoreResult {
    return try {
        KeyboardSettings.restoreBackupPayloadJson(
            context = context,
            backupJson = EncryptedBackupCodec.decrypt(
                encryptedBackup = readBackupFile(context, uri),
                password = password,
            ),
        )
    } finally {
        password.fill('\u0000')
    }
}

private fun readBackupFile(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Could not open import file.")
    val bytes = input.use { source ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BACKUP_READ_BUFFER_BYTES)
        var totalBytes = 0
        while (true) {
            val bytesRead = source.read(buffer)
            if (bytesRead < 0) break
            totalBytes += bytesRead
            if (totalBytes > MAX_ENCRYPTED_BACKUP_BYTES) {
                throw IOException("Backup is too large to import.")
            }
            output.write(buffer, 0, bytesRead)
        }
        output.toByteArray()
    }
    return String(bytes, Charsets.UTF_8)
}

internal fun suggestedBackupFileName(): String {
    return "znkeyboard-backup-${SettingsBackupDefaults.FILE_TIMESTAMP_FORMAT.format(LocalDateTime.now())}.znkb"
}

private const val BACKUP_READ_BUFFER_BYTES = 8 * 1024
private const val MAX_ENCRYPTED_BACKUP_BYTES = 32 * 1024 * 1024

internal data class ModelOption(
    val id: String,
    val name: String?,
)

internal data class PickerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
)
