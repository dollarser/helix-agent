package com.helix.app.foreground

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.helix.app.MainActivity
import com.helix.app.R

/**
 * The `dataSync` foreground service for user-initiated Provider/MCP transport or local file
 * processing (roadmap HXA-066, architecture doc 5.1). It is brought up by
 * [DataSyncForegroundController] only while a turn is actively moving data and torn down the
 * moment the turn waits for the user — so it is never a background residency. Android 15 (API 35)
 * bounds a `dataSync` foreground service to 6 h and then invokes [onTimeout] (which stops it);
 * the notification also carries an explicit stop action.
 *
 * This service holds no socket and does no model/tool work — the transport itself lives in the
 * provider / `http.fetch` ports; here it only keeps that user-initiated work visible and
 * stoppable while the system is allowed to let it run.
 */
class DataSyncForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        runningInstance.set(this)
        ensureDataSyncChannel(this)
    }

    override fun onDestroy() {
        runningInstance.set(null)
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopDataSync()
            return START_NOT_STICKY
        }
        startAsForeground()
        return START_NOT_STICKY
    }

    /**
     * Android 15 (API 35) `dataSync` time-limit reached: the system invokes [onTimeout] (instead
     * of crashing us) with a few seconds to stop the dataSync foreground service. We tear it down.
     * This is an API 35 override — on API < 35 the framework never calls it, so the method simply
     * exists and is dead. [startId] / [fgsType] name the timed-out start and foreground-service
     * type (dataSync here); we stop unconditionally since this service is only ever dataSync.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        stopDataSync()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun stopDataSync() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, DataSyncForegroundService::class.java).setAction(ACTION_STOP)
        val stopAction =
            NotificationCompat.Action
                .Builder(
                    0,
                    getString(R.string.data_sync_action_stop),
                    PendingIntent.getService(
                        this,
                        1,
                        stopIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.data_sync_notification_title))
            .setContentText(getString(R.string.data_sync_notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "data_sync"
        const val NOTIFICATION_ID = 4865
        const val ACTION_STOP = "com.helix.app.foreground.DATA_SYNC_STOP"

        /**
         * Device-test evidence slot (same pattern as [com.helix.app.goal.GoalReminderWorker]): the
         * instance that is currently bound, so an instrumented test can invoke [onTimeout] on the
         * live service. Set in [onCreate], cleared in [onDestroy] — lifecycle-bounded, no leak.
         */
        val runningInstance =
            java.util.concurrent.atomic
                .AtomicReference<DataSyncForegroundService?>(null)

        fun intent(context: Context): Intent = Intent(context, DataSyncForegroundService::class.java)
    }
}

/** The production [ForegroundServiceLauncher]: real `startForegroundService` / `stopService`. */
class AndroidForegroundServiceLauncher(
    private val context: Context,
) : ForegroundServiceLauncher {
    override fun start() {
        context.startForegroundService(DataSyncForegroundService.intent(context))
    }

    override fun stop() {
        context.stopService(DataSyncForegroundService.intent(context))
    }
}

/** Creates the dataSync channel once (minSdk 29 always has notification channels). */
internal fun ensureDataSyncChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(DataSyncForegroundService.CHANNEL_ID) == null) {
        val channel =
            NotificationChannel(
                DataSyncForegroundService.CHANNEL_ID,
                context.getString(R.string.data_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = context.getString(R.string.data_sync_channel_description)
        manager.createNotificationChannel(channel)
    }
}
