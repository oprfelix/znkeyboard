package dev.zain.znkeyboard.constants

/** The anonymous daily ping behind the usage numbers on znkeyboard.2amz.com. */
object UsageStatsDefaults {
    const val PING_URL = "https://znkeyboard.2amz.com/api/ping"
    /** One ping a day at most; a little under a day so a daily job's drift doesn't skip a day. */
    const val MIN_INTERVAL_MS = 20L * 60 * 60 * 1000
    /** Uses one ping may report, as the server caps them. */
    const val MAX_USES_PER_PING = 1_000
    const val TIMEOUT_MS = 10_000
}
