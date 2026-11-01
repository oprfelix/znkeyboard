package dev.zain.znkeyboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import dev.zain.znkeyboard.constants.UpdateDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads a release's APK into the app's cache and hands it to the system installer, which asks
 * the user to confirm (Android never installs an app silently outside an app store; after the first
 * update it may skip the question for later ones). The APK must be this app, at the promised
 * version, signed with the same key; the system checks the key again itself.
 */
object UpdateInstaller {
    sealed interface Outcome {
        /** Handed to the system installer; its confirmation follows. */
        data object Started : Outcome
        /** "Install unknown apps" is off for ZnKeyboard; its settings page was opened. */
        data object NeedsPermission : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** [progress]: 0..1 while downloading. */
    suspend fun install(context: Context, release: UpdateChecker.Release, progress: (Float) -> Unit): Outcome {
        if (!canInstall(context)) {
            openInstallPermission(context)
            return Outcome.NeedsPermission
        }
        val url = release.downloadUrl.takeIf { it.endsWith(".apk", ignoreCase = true) && it.startsWith("https://github.com/") }
            ?: return Outcome.Failed(context.getString(R.string.update_install_no_apk))
        return withContext(Dispatchers.IO) {
            val apk = runCatching { download(context, url, progress) }.getOrElse {
                return@withContext Outcome.Failed(context.getString(R.string.update_install_download_failed))
            }
            if (!isExpected(context, apk, release.version)) {
                apk.delete()
                return@withContext Outcome.Failed(context.getString(R.string.update_install_not_trusted))
            }
            runCatching { commit(context, apk) }.fold(
                onSuccess = { Outcome.Started },
                onFailure = { Outcome.Failed(context.getString(R.string.update_install_failed)) },
            )
        }
    }

    private suspend fun download(context: Context, url: String, progress: (Float) -> Unit): File {
        val folder = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(folder, "ZnKeyboard.apk")
        var location = URL(url)
        // GitHub answers with a redirect to its download host; follow a few, over HTTPS only.
        repeat(5) {
            val connection = location.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = UpdateDefaults.TIMEOUT_MS
                connection.readTimeout = UpdateDefaults.TIMEOUT_MS * 3
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val total = connection.contentLengthLong
                        connection.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    if (total > 0) progress(done.toFloat() / total)
                                }
                            }
                        }
                        return target
                    }
                    in 300..399 -> {
                        val next = connection.getHeaderField("Location") ?: error("redirect without a location")
                        location = URL(location, next)
                        if (location.protocol != "https") error("not https")
                    }
                    else -> error("download answered ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("too many redirects")
    }

    /** This package, the promised version, and signed by the same certificate as the running app. */
    private fun isExpected(context: Context, apk: File, version: String): Boolean {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != context.packageName || archive.versionName != version) return false
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet() ?: return false
        val ours = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet() ?: return false
        return theirs.isNotEmpty() && theirs == ours
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once ZnKeyboard installed itself, later updates may go through without asking.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("ZnKeyboard.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }
}

/** The system installer's answers: shows its confirmation, or says why it stopped. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> File(context.cacheDir, "updates").deleteRecursively()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // The user said no.
            else -> Toast.makeText(context, context.getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
        }
    }
}
