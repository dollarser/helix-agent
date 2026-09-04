package com.helix.tools.android

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * The production [NotificationsBridge] (roadmap HXA-065, doc 09 §11 / doc `overview.md` §11).
 * Context-backed: it reads the real active-notification snapshot held by [HelixNotificationListenerService]
 * and gates the whole query on the user having enabled that listener for this app.
 *
 * - The permission gate runs FIRST: unless [permissionProbe] reports the listener enabled, the query
 *   returns [NotificationQueryStatus.PERMISSION_MISSING] with empty entries — the stable, explicit
 *   "listener not enabled" signal (doc 09: 不能返回空列表冒充成功). It never returns an empty list as a
 *   successful read.
 * - The snapshot read + the allowlist/time-window filter ([filterNotifications]) are bounded: at most
 *   [MAX_NOTIFICATION_ENTRIES] entries, each title/text capped, so a burst of notifications can never
 *   overflow the tool's output schema.
 *
 * [permissionProbe] and [source] are injectable seams so the instrumented test proves the real gating +
 * filtering logic without depending on the (flaky, user-gated) live listener; the production defaults
 * below are what the app container uses. The port never throws for a system condition; a genuine
 * failure is a stable ERROR outcome.
 */
class NotificationsBridgeImpl(
    private val context: Context,
    private val permissionProbe: NotificationPermissionProbe = RealNotificationPermissionProbe(context),
    private val source: NotificationSource = HelixNotificationSource(),
) : NotificationsBridge {
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // stable outcomes, never a crash
    override fun query(request: NotificationQueryRequest): NotificationQueryOutcome {
        if (!permissionProbe.isListenerEnabled()) {
            return NotificationQueryOutcome(
                status = NotificationQueryStatus.PERMISSION_MISSING,
                entries = emptyList(),
                excludedCount = 0,
                reason = "permission-missing",
            )
        }
        return try {
            val raw = source.activeRaw()
            val (entries, excluded) =
                filterNotifications(
                    raw = raw,
                    allowedPackages = request.allowedPackages.toSet(),
                    sinceEpochMillis = request.sinceEpochMillis,
                    untilEpochMillis = request.untilEpochMillis,
                )
            NotificationQueryOutcome(NotificationQueryStatus.QUERIED, entries, excluded, "")
        } catch (e: Exception) {
            NotificationQueryOutcome(NotificationQueryStatus.ERROR, emptyList(), 0, "notification query failed")
        }
    }
}

/**
 * Whether the user has enabled this app's system Notification Listener (the gate, doc 09 §11). A seam
 * so the device test can assert both states deterministically; the production default reads the live
 * `enabled_notification_listeners` setting.
 */
interface NotificationPermissionProbe {
    fun isListenerEnabled(): Boolean
}

/**
 * The production [NotificationPermissionProbe]. `Settings.Secure.enabled_notification_listeners` is a
 * colon-separated list of `package/ComponentName` for every enabled listener (API 29+); this app's
 * listener is enabled iff that list names [HelixNotificationListenerService]. Reading the setting is
 * always permitted (no runtime permission), so a blank/absent list simply means "not enabled" → the
 * query returns PERMISSION_MISSING (never a fake empty success).
 */
class RealNotificationPermissionProbe(
    private val context: Context,
) : NotificationPermissionProbe {
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // unreadable setting = not enabled
    override fun isListenerEnabled(): Boolean {
        return try {
            val flat =
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?: return false
            flat.contains(HelixNotificationListenerService.SERVICE_FQCN)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * The (Android-backed) source of the active-notification snapshot. A seam so the device test can feed
 * canned [RawNotification]s through the real production filter; the production default reads the
 * snapshot held by [HelixNotificationListenerService].
 */
interface NotificationSource {
    fun activeRaw(): List<RawNotification>
}

/** The production [NotificationSource]: the live snapshot held by [HelixNotificationListenerService]. */
class HelixNotificationSource : NotificationSource {
    override fun activeRaw(): List<RawNotification> = HelixNotificationStore.snapshot()
}

/**
 * The live active-notification snapshot, maintained by [HelixNotificationListenerService] as the user
 * enables it and the system posts/removes notifications. Keyed by the notification key (a package may
 * have many active notifications). Pure JVM (holds [RawNotification]s only), so it needs no Android
 * runtime to reason about.
 */
internal object HelixNotificationStore {
    private val byKey = ConcurrentHashMap<String, RawNotification>()

    fun put(
        key: String,
        raw: RawNotification,
    ) {
        byKey[key] = raw
    }

    fun remove(key: String) {
        byKey.remove(key)
    }

    fun clear() {
        byKey.clear()
    }

    fun snapshot(): List<RawNotification> = byKey.values.toList()
}

/**
 * The system [NotificationListenerService] that makes `notifications.query` actually able to read
 * other apps' active notifications. It only runs once the user enables "Notification access" for Helix
 * in system settings (doc 09 §11); until then it is never bound and [RealNotificationPermissionProbe]
 * reports not-enabled, so the query returns PERMISSION_MISSING. It maintains [HelixNotificationStore]
 * from the platform callbacks and holds no other state.
 */
class HelixNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        HelixNotificationStore.clear()
        for (sbn in activeNotifications) {
            HelixNotificationStore.put(sbn.key, toRaw(sbn))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        HelixNotificationStore.put(sbn.key, toRaw(sbn))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        HelixNotificationStore.remove(sbn.key)
    }

    override fun onListenerDisconnected() {
        HelixNotificationStore.clear()
    }

    private fun toRaw(sbn: StatusBarNotification): RawNotification {
        val extras = sbn.notification.extras
        return RawNotification(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            postedEpochMillis = sbn.postTime,
        )
    }

    companion object {
        /** The service's fully-qualified class name (matched by the permission probe against the setting). */
        const val SERVICE_FQCN: String = "com.helix.tools.android.HelixNotificationListenerService"
    }
}
