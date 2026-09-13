package com.helix.runtime.cli.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import java.util.UUID

/** User-requested network transfer only; one bound operation, no restart or request replay. */
internal class SubscriptionNetworkForeground(
    private val service: Service,
    private val cancel: () -> Unit,
) : AutoCloseable {
    private var lease: String? = null

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // Roll back foreground state and rethrow the original platform failure.
    fun begin() {
        if (lease != null) return
        val identity = Binder.clearCallingIdentity()
        try {
            val token = UUID.randomUUID().toString()
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    service.getString(R.string.subscription_network_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val stopIntent =
                Intent(service, CliRuntimeService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_LEASE, token)
            val stop =
                PendingIntent.getService(
                    service,
                    0,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                Notification
                    .Builder(service, CHANNEL)
                    .setSmallIcon(R.drawable.ic_helix)
                    .setContentTitle(service.getString(R.string.subscription_app_name))
                    .setContentText(service.getString(R.string.subscription_network_running))
                    .setUsesChronometer(true)
                    .setOngoing(true)
                    .addAction(
                        Notification.Action
                            .Builder(
                                null,
                                service.getString(R.string.subscription_network_stop),
                                stop,
                            ).build(),
                    ).build()
            service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            service.startService(Intent(service, CliRuntimeService::class.java).setAction(ACTION_RUNNING))
            lease = token
        } catch (failure: RuntimeException) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            service.stopSelf()
            throw failure
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @Synchronized
    fun handle(intent: Intent?) {
        if (lease == null) {
            close()
            return
        }
        if (intent?.action == ACTION_STOP && intent.getStringExtra(EXTRA_LEASE) == lease && lease != null) stop()
    }

    fun stop() {
        try {
            cancel()
        } finally {
            close()
        }
    }

    @Synchronized
    override fun close() {
        lease = null
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        service.stopSelf()
    }

    private companion object {
        const val CHANNEL = "subscription-network"
        const val ID = 7044
        const val ACTION_STOP = "com.helix.runtime.cli.STOP_NETWORK"
        const val ACTION_RUNNING = "com.helix.runtime.cli.NETWORK_RUNNING"
        const val EXTRA_LEASE = "network_lease"
    }
}
