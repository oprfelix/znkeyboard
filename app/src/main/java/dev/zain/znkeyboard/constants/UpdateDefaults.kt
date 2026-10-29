package dev.zain.znkeyboard.constants

object UpdateDefaults {
    /** GitHub repository whose releases ship the APK. */
    const val REPOSITORY = "oprfelix/znkeyboard"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val PROJECT_URL = "https://github.com/$REPOSITORY"
    const val RELEASES_URL = "$PROJECT_URL/releases"

    /** Automatic checks run when settings open, at most this often. */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val TIMEOUT_MS = 10_000

    /** Release notes past this are cut in the update dialog; the release page has the rest. */
    const val MAX_NOTES_CHARS = 1_500
}
