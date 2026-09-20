package com.helix.runtime.proot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.helix.runtime.proot.ipc.PtySessionKey
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit manual start only. START_NOT_STICKY never resurrects or replays a shell. */
class ProotTerminalService : Service() {
    private lateinit var host: ProotTerminalHost
    private lateinit var endpoint: ProotTerminalEndpoint
    private val monitor = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var promotion: Promotion? = null

    private class Promotion(
        val key: PtySessionKey,
    ) {
        val ticket = UUID.randomUUID().toString()
        val ready = CountDownLatch(1)

        @Volatile var foreground = false
    }

    override fun onCreate() {
        super.onCreate()
        host = ProotTerminalHost(this)
        endpoint = ProotTerminalEndpoint(this, host)
        monitor.scheduleWithFixedDelay(::tick, 200, 200, TimeUnit.MILLISECONDS)
    }

    override fun onBind(intent: Intent): IBinder = endpoint

    internal fun promote(key: PtySessionKey): Boolean {
        val current = Promotion(key)
        promotion = current
        val identity = Binder.clearCallingIdentity()
        try {
            startForegroundService(Intent(this, ProotTerminalService::class.java).putExtra("ticket", current.ticket))
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return current.ready.await(4, TimeUnit.SECONDS) && current.foreground
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Ticket, stop and promotion are separate lifecycle outcomes.
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val current = promotion
        if (current == null || intent?.getStringExtra("ticket") != current.ticket) {
            if (current == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == STOP_ACTION) {
            monitor.execute { host.stop(current.key) }
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(NOTIFICATION_ID, notification(current), type)
            current.foreground = true
        } catch (failure: Exception) {
            android.util.Log.e("ManualPty", "Manual terminal foreground unavailable", failure)
            stopSelf(startId)
        } finally {
            current.ready.countDown()
        }
        return START_NOT_STICKY
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tick() {
        try {
            synchronized(host) {
                if (!host.tick() && promotion?.foreground == true) {
                    promotion = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } catch (failure: Exception) {
            android.util.Log.e("ManualPty", "Manual terminal observation failed", failure)
            host.destroy()
        }
    }

    override fun onDestroy() {
        host.destroy()
        endpoint.close()
        promotion?.ready?.countDown()
        monitor.shutdown()
        super.onDestroy()
    }

    private fun notification(current: Promotion): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.proot_terminal_title), NotificationManager.IMPORTANCE_LOW),
        )
        val stop =
            Intent(
                this,
                ProotTerminalService::class.java,
            ).setAction(STOP_ACTION).putExtra("ticket", current.ticket)
        val action =
            PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_helix)
            .setContentTitle(getString(R.string.proot_terminal_title))
            .setContentText(getString(R.string.proot_terminal_running))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.proot_job_stop), action).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "proot-terminal"
        private const val NOTIFICATION_ID = 197
        private const val STOP_ACTION = "com.helix.runtime.proot.STOP_MANUAL_TERMINAL"
    }
}
