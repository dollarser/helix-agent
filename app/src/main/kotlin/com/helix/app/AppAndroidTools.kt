package com.helix.app

import android.content.Context
import com.helix.tools.android.AndroidSystemBridgeImpl
import com.helix.tools.android.AndroidSystemTools
import com.helix.tools.android.CalendarBridgeImpl
import com.helix.tools.android.EgressPolicyProvider
import com.helix.tools.android.HttpFetchBridgeImpl
import com.helix.tools.android.HttpFetchTools
import com.helix.tools.android.NotificationsBridgeImpl
import com.helix.tools.android.NotificationsCalendarTools
import com.helix.tools.framework.ToolRegistry

internal object AppAndroidTools {
    fun register(
        context: Context,
        toolRegistry: ToolRegistry,
        egressPolicy: EgressPolicyProvider,
    ) {
        com.helix.app.tool.DevicePageTool
            .register(toolRegistry)
        // HXA-064+: Android URI/settings + clipboard/share tools.
        // The production port (AndroidSystemBridgeImpl) is Context-backed: it builds the real
        // Intent / ClipboardManager calls and gates clipboard read/write on visible-foreground.
        // External actions use the ordinary dispatcher/policy path; opening Settings never toggles it itself.
        AndroidSystemTools.registerAll(
            toolRegistry,
            AndroidSystemBridgeImpl(context),
        )
        // HXA-065: the device.notifications_query / device.prepare_calendar_event / device.create_calendar_event tools.
        // The production ports are Context-backed: NotificationsBridgeImpl reads the live snapshot
        // held by the (manifest-declared) NotificationListenerService and gates the whole query on the
        // user enabling "Notification access"; CalendarBridgeImpl holds in-memory drafts (prepare
        // never writes) and writes the held draft to the Calendar Provider on commit, gated on
        // WRITE_CALENDAR. Both return a stable 'permission-missing' (never a fake success) when the
        // permission is off (doc 09 §11 / overview.md §11).
        NotificationsCalendarTools.registerAll(
            toolRegistry,
            NotificationsBridgeImpl(context),
            CalendarBridgeImpl(context),
        )
        com.helix.app.knowledge.KnowledgeReadTools.register(
            toolRegistry,
            (context.applicationContext as HelixApplication).knowledge.agentAccess,
        )
        val reminders = (context.applicationContext as HelixApplication).reminders
        com.helix.app.reminder.DeviceReminderTools.register(
            toolRegistry,
            object : com.helix.app.reminder.DeviceReminderTools.Backend {
                override suspend fun list() = reminders.list()

                override suspend fun save(reminder: com.helix.core.storage.reminder.LocalReminder) =
                    requireNotNull(reminders.get(reminders.save(reminder)))

                override suspend fun cancel(
                    id: String,
                    revision: Long,
                ) = reminders.cancelRevision(id, revision)
            },
        )
        // HXA-066: the http.fetch tool — a bounded GET/HEAD over the raw-socket transport
        // (HttpFetchBridgeImpl) that enforces the connection-time SSRF / URL-Policy: it resolves
        // every A/AAAA/IPv4-mapped candidate, connects only to the verified set, revalidates the
        // actual peer, keeps the original hostname for TLS Host/SNI/cert, and re-runs the whole
        // origin/DNS/IP/scope decision on every redirect hop. The egress decision (current
        // SafetyProfile + the user's pre-created exact LAN/loopback scopes) is read from the APP's
        // profileStore and explicit user-created scopes, NEVER the model. Scopes are reread
        // at every connection/redirect; Advanced alone never creates one.
        HttpFetchTools.registerAll(
            toolRegistry,
            HttpFetchBridgeImpl(
                egressPolicy,
            ),
        )
    }
}
