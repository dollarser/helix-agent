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
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry

internal object AppAndroidTools {
    fun register(
        context: Context,
        toolRegistry: ToolRegistry,
        toolImplementations: ToolImplementationRegistry,
        egressPolicy: EgressPolicyProvider,
    ) {
        // HXA-064: the android.open_uri / clipboard.read / clipboard.write / android.share tools.
        // The production port (AndroidSystemBridgeImpl) is Context-backed: it builds the real
        // Intent / ClipboardManager calls and gates clipboard read/write on visible-foreground.
        // All four are L2 EXTERNAL_ACTION, so the L2 approval card previews the FULL arguments
        // (e.g. the share text) before the user approves — that IS "分享输入先预览" (doc 02 §5.4).
        AndroidSystemTools.registerAll(
            toolRegistry,
            toolImplementations,
            AndroidSystemBridgeImpl(context),
        )
        // HXA-065: the notifications.query / calendar.prepare_event / calendar.commit_event tools.
        // The production ports are Context-backed: NotificationsBridgeImpl reads the live snapshot
        // held by the (manifest-declared) NotificationListenerService and gates the whole query on the
        // user enabling "Notification access"; CalendarBridgeImpl holds in-memory drafts (prepare
        // never writes) and writes the held draft to the Calendar Provider on commit, gated on
        // WRITE_CALENDAR. Both return a stable 'permission-missing' (never a fake success) when the
        // permission is off (doc 09 §11 / overview.md §11).
        NotificationsCalendarTools.registerAll(
            toolRegistry,
            toolImplementations,
            NotificationsBridgeImpl(context),
            CalendarBridgeImpl(context),
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
            toolImplementations,
            HttpFetchBridgeImpl(
                egressPolicy,
            ),
        )
    }
}
