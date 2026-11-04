package dev.zain.znkeyboard

import android.content.Context
import dev.zain.znkeyboard.constants.UsageStatsDefaults
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The anonymous daily ping behind the usage numbers on znkeyboard.2amz.com: a random id made on this
 * phone, the platform, the version and how many times text was polished or translated since the last
 * ping. Never any text, key or setting.
 *
 * The keyboard itself never goes online for it: it only notes the day it was used, and how many AI
 * results it produced, in a private preferences file (not part of settings backups). The daily
 * [UsagePingJob] and the settings app send the ping, and only for a day the keyboard was used, so
 * the count is of people using ZnKeyboard, not of phones it sits on. Off with the setting.
 */
/** The installed version's name, as the About section and the ping show it. */
fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

object UsageStats {
    private const val PREFS_NAME = "usage_stats"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_USED_DAY = "used_day"
    private const val KEY_LAST_PING_AT = "last_ping_at"
    private const val KEY_PENDING_USES = "pending_uses"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** The day last written, so showing the keyboard writes at most once a day. */
    @Volatile private var markedDay = -1L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun today() = System.currentTimeMillis() / DAY_MS

    /** The keyboard (or settings) was used today. Cheap: one comparison unless the day changed. */
    fun markUsed(context: Context) {
        val day = today()
        if (day == markedDay) return
        markedDay = day
        prefs(context).edit().putLong(KEY_USED_DAY, day).apply()
    }

    /** One finished polish, translation or AI voice input. */
    fun recordUse(context: Context) {
        if (!KeyboardSettings.readAnonymousStats(context)) return
        val prefs = prefs(context)
        prefs.edit().putInt(KEY_PENDING_USES, prefs.getInt(KEY_PENDING_USES, 0) + 1).apply()
    }

    /**
     * Sends the ping when it is on, the keyboard was used since the last one and a day has passed.
     * Blocking; call off the main thread. A failed ping is tried next time, uses kept.
     */
    fun pingIfDue(context: Context) {
        if (!KeyboardSettings.readAnonymousStats(context)) return
        val prefs = prefs(context)
        val lastPing = prefs.getLong(KEY_LAST_PING_AT, 0L)
        val usedDay = prefs.getLong(KEY_USED_DAY, -1L)
        if (usedDay < 0 || usedDay <= lastPing / DAY_MS && lastPing > 0) return
        if (System.currentTimeMillis() - lastPing < UsageStatsDefaults.MIN_INTERVAL_MS) return
        val id = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
        val uses = prefs.getInt(KEY_PENDING_USES, 0).coerceIn(0, UsageStatsDefaults.MAX_USES_PER_PING)
        val body = JSONObject()
            .put("id", id)
            .put("platform", "android")
            .put("version", appVersion(context).substringBefore('-'))
            .put("uses", uses)
            .toString()
        val sent = runCatching {
            val connection = URL(UsageStatsDefaults.PING_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = UsageStatsDefaults.TIMEOUT_MS
                connection.readTimeout = UsageStatsDefaults.TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        if (!sent) return
        prefs.edit()
            .putLong(KEY_LAST_PING_AT, System.currentTimeMillis())
            .putInt(KEY_PENDING_USES, (prefs.getInt(KEY_PENDING_USES, 0) - uses).coerceAtLeast(0))
            .apply()
    }
}
