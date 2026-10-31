package com.helix.app.scheduled

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.helix.app.MainActivity
import com.helix.app.R

/** A result projection only: posting or tapping never authorizes/replays an Agent turn. */
internal data class ScheduledResultFact(
    val ruleId: String,
    val attemptId: String,
    val state: String,
)

internal class ScheduledResultNotification(
    private val context: Context,
) {
    fun post(fact: ScheduledResultFact): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permitted =
            Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && manager.areNotificationsEnabled() && postAvailable(manager, fact)
    }

    private fun postAvailable(
        manager: NotificationManager,
        fact: ScheduledResultFact,
    ): Boolean {
        val uri =
            Uri
                .Builder()
                .scheme("helix")
                .authority("automation")
                .appendPath(fact.ruleId)
                .appendQueryParameter("requestId", fact.attemptId)
                .build()
        if (ScheduledOpenRequest.parse(uri.toString()) == null) return false
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.scheduled_result_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val channel = manager.getNotificationChannel(CHANNEL)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE &&
            deliver(manager, fact, uri)
    }

    private fun deliver(
        manager: NotificationManager,
        fact: ScheduledResultFact,
        uri: Uri,
    ): Boolean {
        val intent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text =
            if (fact.state ==
                "COMPLETED"
            ) {
                R.string.scheduled_result_done
            } else {
                R.string.scheduled_result_attention
            }
        val notification =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(
                    context.getString(R.string.scheduled_result_channel),
                ).setContentText(context.getString(text))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
        return try {
            manager.notify("scheduled-result:${fact.attemptId}", 0, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        const val CHANNEL = "scheduled-agent-results"
    }
}
