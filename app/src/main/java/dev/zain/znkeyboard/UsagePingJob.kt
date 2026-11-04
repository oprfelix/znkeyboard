package dev.zain.znkeyboard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Once a day, with a network, sends the anonymous usage ping ([UsageStats]) while its setting is on. */
class UsagePingJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!KeyboardSettings.readAnonymousStats(this)) return false
        worker = Thread {
            runCatching { UsageStats.pingIfDue(this) }
            jobFinished(params, false)
        }.apply {
            name = "ZnKeyboardUsagePing"
            start()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val JOB_ID = 4202
        /** The job that also checked for updates, until Google Play took those over. */
        private const val OLD_JOB_ID = 4201
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Keeps the daily ping scheduled (or cancelled) to match the setting; cheap to call often.
         * Never throws: it runs as the settings app and the keyboard start, and the system refuses
         * some jobs (1.7.1 crashed on launch when it did).
         */
        fun sync(context: Context) {
            runCatching { syncOrThrow(context) }
        }

        private fun syncOrThrow(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(OLD_JOB_ID)
            if (!KeyboardSettings.readAnonymousStats(context)) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UsagePingJob::class.java))
                    .setPeriodic(DAY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
