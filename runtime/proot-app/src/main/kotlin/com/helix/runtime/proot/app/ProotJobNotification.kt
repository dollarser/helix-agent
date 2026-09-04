package com.helix.runtime.proot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The job-running notification (HXA-086 通知停止): while a job is RUNNING the
 * companion shows ONE plain notification with a 停止 (stop) action.
 *
 * Deliberate boundaries:
 * - This is a PLAIN notification, NOT a foreground service: the companion
 *   stays bound-only with zero FGS and zero wake lock (084 discipline;
 *   "任意计算不得冒充 `dataSync`"). When the last binder unbinds, the process
 *   (and with it the notification) is reclaimable — a job that survives an
 *   unbind is an orphan the next start sweeps, it is never guaranteed to
 *   outlive its client.
 * - The stop action is a signature-permission-protected broadcast (the same
 *   permission that guards the service): only same-signed-set APKs (the main
 *   app, the developer test APK) can trigger it. The receiver is the ONLY
 *   non-bind surface of the companion besides the repair activity.
 * - The notification carries the job id only — no command text, no
 *   environment, no paths: nothing secret or model-context-shaped reaches
 *   the system tray.
 * - POST_NOTIFICATIONS (API 33+) is a runtime permission; when it is not
 *   granted the platform silently drops the post and the job runs
 *   unchanged — the notification is a convenience surface, never a
 *   liveness or correctness dependency (the binder + journal are).
 */
internal object ProotJobNotification {
    private const val CHANNEL_ID = "proot-jobs"
    private const val TAG_PREFIX = "proot-job-"
    private const val STOP_ACTION = "com.helix.runtime.proot.action.STOP_JOB"
    private const val EXTRA_JOB_ID = "job_id"
    private const val NOTIFICATION_ID_BASE = 0x504F3431 // "PROOT1"

    /** Posts (or re-posts) the running-job notification. Idempotent per job. */
    fun postRunning(
        context: Context,
        jobId: String,
    ) {
        val appContext = context.applicationContext
        createChannel(appContext)
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val stopIntent =
            Intent(STOP_ACTION).apply {
                setPackage(appContext.packageName)
                putExtra(EXTRA_JOB_ID, jobId)
            }
        // minSdk 29 >= M: FLAG_IMMUTABLE is always required (API 31+ enforcement).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopAction =
            Notification.Action
                .Builder(
                    null,
                    appContext.getString(R.string.proot_job_stop),
                    PendingIntent.getBroadcast(appContext, 0, stopIntent, flags),
                ).build()
        val notification =
            Notification
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_helix)
                .setContentTitle(appContext.getString(R.string.proot_job_running_title))
                .setContentText(jobId)
                .setStyle(
                    Notification.BigTextStyle().bigText(jobId),
                ).addAction(stopAction)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .build()
        @Suppress("TooGenericExceptionCaught", "SwallowedException") // notification posting must never kill the job
        try {
            manager.notify(tagFor(jobId), notificationIdFor(jobId), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+): the platform refuses;
            // the job is unaffected — this is a convenience surface only.
        }
    }

    /** Removes the job's notification. Called from EVERY terminal path. */
    fun cancel(
        context: Context,
        jobId: String,
    ) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            manager.cancel(tagFor(jobId), notificationIdFor(jobId))
        } catch (e: SecurityException) {
            // Same as postRunning: never a correctness dependency.
        }
    }

    /** The stop broadcast target (the PendingIntent action and the test share it). */
    fun stopBroadcastIntent(
        context: Context,
        jobId: String,
    ): Intent =
        Intent(STOP_ACTION).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_JOB_ID, jobId)
        }

    private fun createChannel(context: Context) {
        // minSdk 29 >= O: channels are always available.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.proot_job_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun tagFor(jobId: String) = TAG_PREFIX + jobId

    private fun notificationIdFor(jobId: String) = NOTIFICATION_ID_BASE xor jobId.hashCode()
}
