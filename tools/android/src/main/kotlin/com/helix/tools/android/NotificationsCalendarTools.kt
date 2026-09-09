@file:Suppress("TooManyFunctions") // 3 notifications.*/calendar.* tools share the internal schema/arg helpers

package com.helix.tools.android

import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Model-facing input bounds (defensive re-reads; the input schema already enforces these).
internal const val MAX_ALLOWED_PACKAGES: Int = 16
internal const val MAX_PACKAGE_LEN: Int = 128
internal const val MAX_NOTIFICATION_WINDOW_MS: Long = 24L * 86_400_000
internal const val MAX_EVENT_TITLE: Int = 200
internal const val MAX_EVENT_LOCATION: Int = 200
internal const val MAX_EVENT_NOTES: Int = 2000
internal const val MAX_EVENT_TIMEZONE: Int = 64
internal const val MAX_DRAFT_ID: Int = 64
internal const val MAX_EVENT_ID: Int = 128

// Stable outcome status strings; they appear in BOTH the output schema enum and the emitted output,
// so a one-sided drift is impossible.
internal const val ST_QUERIED: String = "queried"
internal const val ST_PERMISSION_MISSING: String = "permission-missing"
internal const val ST_PREPARED: String = "prepared"
internal const val ST_INVALID: String = "invalid"
internal const val ST_COMMITTED: String = "committed"
internal const val ST_DRAFT_NOT_FOUND: String = "draft-not-found"
internal const val CALENDAR_NO_HANDLER: String = "no-handler"

// The module's shared scalar/object helpers do not cover arrays, so the array schema is built here.
internal fun notificationsCalendarToolsArraySchema(
    items: JsonObject,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", items)
        put("description", JsonPrimitive(description))
    }

/** Reads an integer argument (present, a number, a valid long). Null when absent/malformed. */
internal fun notificationsCalendarToolsIntArg(
    args: JsonObject,
    key: String,
): Long? = (args[key] as? JsonPrimitive)?.content?.toLongOrNull()

// The notifications.query entries-array item schema (a bounded package/title/text + post time).
internal fun notificationsCalendarToolsNotificationEntrySchema(): JsonObject =
    objectSchema(
        properties =
            buildJsonObject {
                put("packageName", stringSchema(MAX_PACKAGE_LEN, "The posting app's package."))
                put("title", stringSchema(MAX_NOTIFICATION_TITLE, "Bounded notification title."))
                put("text", stringSchema(MAX_NOTIFICATION_TEXT, "Bounded notification text."))
                put("postedEpochMillis", integerSchema("Post time (epoch millis)."))
            },
        required = listOf("packageName", "title", "text", "postedEpochMillis"),
    )

// ===========================================================================
// notifications.query — read an allowlist- and window-bounded notification snapshot
// ===========================================================================

// ===========================================================================
// calendar.prepare_event — build + hold a structured draft (no write, no permission)
// ===========================================================================

// ===========================================================================
// calendar.commit_event — the single write step (WRITE_CALENDAR-gated, L2, every call approved)
// ===========================================================================

// ===========================================================================
// Registration
// ===========================================================================
object NotificationsCalendarTools {
    /**
     * Registers the three `notifications.*` / `calendar.*` contracts and implementations against the
     * shared [notifications] / [calendar] bridges. Called once from the app container (which owns the
     * production [NotificationsBridgeImpl] / [CalendarBridgeImpl]); tests build a [ToolRegistry] /
     * [ToolImplementationRegistry] pair and fake bridges.
     */
    fun registerAll(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        notifications: NotificationsBridge,
        calendar: CalendarBridge,
    ) {
        NotificationsQueryTool.register(registry, implementations, notifications)
        CalendarPrepareEventTool.register(registry, implementations, calendar)
        CalendarCommitEventTool.register(registry, implementations, calendar)
    }
}
