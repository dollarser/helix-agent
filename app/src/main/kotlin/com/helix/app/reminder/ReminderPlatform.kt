package com.helix.app.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent

interface ReminderDelivery {
    fun schedule(item: LocalReminder): String

    fun cancel(item: LocalReminder)

    fun post(item: LocalReminder): String

    fun posted(event: ReminderEvent): Boolean

    fun dismiss(id: String)
}

@Suppress("TooManyFunctions") // Android alarm, permission and notification adapter.
class ReminderPlatform(
    private val context: Context,
) : ReminderDelivery {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.reminder_title),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun notificationsAllowed(): Boolean {
        ensureChannel()
        return notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    override fun schedule(item: LocalReminder): String {
        val due = requireNotNull(item.nextAt)
        val pending = alarmIntent(item)
        val precise = item.exact && exactAllowed()
        // The grant can change around scheduling. Fall back honestly rather than lose the reminder.
        var scheduledExact = precise
        try {
            if (precise) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
            }
        } catch (_: SecurityException) {
            scheduledExact = false
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        }
        return when {
            !notificationsAllowed() -> "NOTIFICATIONS_OFF"
            scheduledExact -> "EXACT"
            else -> "APPROXIMATE"
        }
    }

    override fun cancel(item: LocalReminder) {
        PendingIntent
            .getBroadcast(
                context,
                0,
                alarmRoute(item.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            )?.let(alarms::cancel)
    }

    override fun post(item: LocalReminder): String {
        if (!notificationsAllowed()) return "BLOCKED"
        val notification =
            Notification
                .Builder(context, CHANNEL)
                .setExtras(
                    android.os.Bundle().apply {
                        putLong("reminder_revision", item.revision)
                        putLong("reminder_due", requireNotNull(item.nextAt))
                    },
                ).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(item.title)
                .setContentText(item.body.ifBlank { context.getString(R.string.reminder_title) })
                .setStyle(Notification.BigTextStyle().bigText(item.body))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent(item.id))
                .setAutoCancel(true)
                .build()
        return try {
            notifications.notify(tag(item.id), 1, notification)
            "POSTED"
        } catch (_: SecurityException) {
            "BLOCKED"
        }
    }

    override fun posted(event: ReminderEvent): Boolean =
        notifications.activeNotifications.any {
            it.tag == tag(event.reminderId) &&
                it.notification.extras.getLong("reminder_revision") == event.revision &&
                it.notification.extras.getLong("reminder_due") == event.dueAt
        }

    override fun dismiss(id: String) = notifications.cancel(tag(id), 1)

    fun contentIntent(id: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setData(
                    Uri
                        .Builder()
                        .scheme("helix")
                        .authority("reminder")
                        .appendPath(id)
                        .build(),
                ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun alarmIntent(item: LocalReminder): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            alarmRoute(item.id).putExtra("revision", item.revision).putExtra("due", requireNotNull(item.nextAt)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // Stable projection identity permits recovery cancellation even after the nextAt field is cleared.
    private fun alarmRoute(id: String) =
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(
                Uri
                    .Builder()
                    .scheme("helix")
                    .authority("reminder-alarm")
                    .appendPath(id)
                    .build(),
            )

    private fun tag(id: String) = "local-reminder:$id"

    companion object {
        const val CHANNEL = "local-reminders-v1"
        const val ACTION_FIRE = "com.helix.reminder.FIRE"
    }
}
