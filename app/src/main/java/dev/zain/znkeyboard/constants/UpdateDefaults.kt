package dev.zain.znkeyboard.constants

object UpdateDefaults {
    /** GitHub repository whose releases ship the APK. */
    const val REPOSITORY = "oprfelix/znkeyboard"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val PROJECT_URL = "https://github.com/$REPOSITORY"
    const val RELEASES_URL = "$PROJECT_URL/releases"

    /** The website, and its mirror on GitHub Pages, as About shows them. */
    const val WEBSITE_URL = "https://znkeyboard.2amz.com/"
    const val MIRROR_URL = "https://oprfelix.github.io/znkeyboard/"

    /**
     * The latest installers on znkeyboard.2amz.com (Cloudflare), which mainland China reaches more
     * reliably than GitHub: checked first, with GitHub's release list and file as the fallback.
     */
    const val CLOUDFLARE_LATEST = "https://znkeyboard.2amz.com/download/latest.json"
    const val CLOUDFLARE_DOWNLOAD = "https://znkeyboard.2amz.com/download/android/"
    const val GITHUB_DOWNLOAD = "$PROJECT_URL/releases/download/"

    /** Automatic checks run when settings open, at most this often. */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val TIMEOUT_MS = 10_000

    /** Release notes past this are cut in the update dialog; the release page has the rest. */
    const val MAX_NOTES_CHARS = 1_500
}
