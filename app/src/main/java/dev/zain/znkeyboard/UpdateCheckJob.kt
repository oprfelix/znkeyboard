package dev.zain.znkeyboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification

/**
 * Once a day, with a network, asks GitHub whether a newer release is out (only the public release
 * metadata, as the settings app's own check does) and posts one notification per new version.
 * Tapping it opens the update dialog. Off when "Check for updates automatically" is off.
 */
class UpdateCheckJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!KeyboardSettings.readUpdateAutoCheck(this)) return false
        worker = Thread {
            runCatching {
                val release = UpdateChecker.fetchLatest()
                KeyboardSettings.saveLatestRelease(this, release, System.currentTimeMillis())
                val current = UpdateChecker.currentVersion(this)
                if (UpdateChecker.isNewer(release.version, current) && KeyboardSettings.readNotifiedUpdateVersion(this) != release.version) {
                    notifyUpdate(this, release.version)
                    KeyboardSettings.saveNotifiedUpdateVersion(this, release.version)
                }
            }
            jobFinished(params, false)
        }.apply {
            name = "ZnKeyboardUpdateCheck"
            start()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val JOB_ID = 4201
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 4201
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Keeps the daily check scheduled (or cancelled) to match the setting; cheap to call often.
         * Never throws: it runs as the settings app and the keyboard start, and the system refuses
         * some jobs (1.7.1 crashed on launch when it did).
         */
        fun sync(context: Context) {
            runCatching { syncOrThrow(context) }
        }

        private fun syncOrThrow(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!KeyboardSettings.readUpdateAutoCheck(context)) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setPeriodic(DAY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        private fun notifyUpdate(context: Context, version: String) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_SHOW_UPDATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle(context.getString(R.string.update_available, version))
                .setContentText(context.getString(R.string.update_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
