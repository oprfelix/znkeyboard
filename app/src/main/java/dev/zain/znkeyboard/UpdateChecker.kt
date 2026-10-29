package dev.zain.znkeyboard

import android.content.Context
import dev.zain.znkeyboard.constants.UpdateDefaults
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub for the latest release. Only the public release metadata is fetched; nothing about
 * the user or device is sent beyond an ordinary HTTPS request. Runs only from the settings app,
 * never from the keyboard.
 */
object UpdateChecker {
    /** A published release: [version] without the leading "v", [downloadUrl] the APK (or the page). */
    data class Release(val version: String, val notes: String, val downloadUrl: String, val pageUrl: String)

    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Blocking; call off the main thread. Throws when GitHub can't be reached or answers oddly. */
    fun fetchLatest(): Release {
        val connection = URL(UpdateDefaults.LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = UpdateDefaults.TIMEOUT_MS
            connection.readTimeout = UpdateDefaults.TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("GitHub answered $code")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v").trim()
            val pageUrl = json.optString("html_url").ifBlank { UpdateDefaults.RELEASES_URL }
            val assets = json.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.startsWith("https://") }
            return Release(version, json.optString("body"), apkUrl ?: pageUrl, pageUrl)
        } finally {
            connection.disconnect()
        }
    }

    /** True when [latest] is a higher dotted version than [current], e.g. 1.10.0 > 1.9.2. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = versionParts(latest)
        val b = versionParts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun versionParts(version: String): List<Int> =
        version.substringBefore('-').split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** Release notes as plain text for a dialog: Markdown marks dropped, long notes cut. */
    fun plainNotes(notes: String): String {
        val text = notes.lineSequence()
            .map { line -> line.trimEnd().replace(Regex("^#{1,6}\\s*"), "").replace("**", "").replace("`", "") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (text.length <= UpdateDefaults.MAX_NOTES_CHARS) text else text.take(UpdateDefaults.MAX_NOTES_CHARS).trimEnd() + "…"
    }
}
