@file:Suppress("TooManyFunctions") // 3 notifications.*/calendar.* tools share the internal schema/arg helpers

package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

// Model-facing input bounds (defensive re-reads; the input schema already enforces these).
private const val MAX_ALLOWED_PACKAGES: Int = 16
private const val MAX_PACKAGE_LEN: Int = 128
private const val MAX_NOTIFICATION_WINDOW_MS: Long = 24L * 86_400_000
private const val MAX_EVENT_TITLE: Int = 200
private const val MAX_EVENT_LOCATION: Int = 200
private const val MAX_EVENT_NOTES: Int = 2000
private const val MAX_EVENT_TIMEZONE: Int = 64
private const val MAX_DRAFT_ID: Int = 64
private const val MAX_EVENT_ID: Int = 128

// Stable outcome status strings; they appear in BOTH the output schema enum and the emitted output,
// so a one-sided drift is impossible.
private const val ST_QUERIED: String = "queried"
private const val ST_PERMISSION_MISSING: String = "permission-missing"
private const val ST_PREPARED: String = "prepared"
private const val ST_INVALID: String = "invalid"
private const val ST_COMMITTED: String = "committed"
private const val ST_DRAFT_NOT_FOUND: String = "draft-not-found"
private const val ST_NO_HANDLER: String = "no-handler"

// The module's shared scalar/object helpers do not cover arrays, so the array schema is built here.
private fun arraySchema(
    items: JsonObject,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", items)
        put("description", JsonPrimitive(description))
    }

/** Reads an integer argument (present, a number, a valid long). Null when absent/malformed. */
private fun intArg(
    args: JsonObject,
    key: String,
): Long? = (args[key] as? JsonPrimitive)?.content?.toLongOrNull()

// The notifications.query entries-array item schema (a bounded package/title/text + post time).
private fun notificationEntrySchema(): JsonObject =
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
object NotificationsQueryTool {
    const val NAME: String = "notifications.query"

    const val VERSION: Int = 1

    @Suppress("LongMethod") // model-facing descriptor kept as one readable block
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Read the active notifications posted by the apps in [allowedPackages] within the " +
                    "[sinceEpochMillis, untilEpochMillis] window (at most 24h; newest first; bounded to " +
                    "20 entries). Refused with status 'permission-missing' unless the user has enabled " +
                    "Helix's system Notification Listener. Only the allowlisted apps' notifications are " +
                    "returned; the count of in-window notifications filtered out is also reported.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "allowedPackages",
                                arraySchema(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put("maxLength", JsonPrimitive(MAX_PACKAGE_LEN))
                                    },
                                    "The exact app package names to read (1..$MAX_ALLOWED_PACKAGES).",
                                ),
                            )
                            put("sinceEpochMillis", integerSchema("Window start (epoch millis, inclusive)."))
                            put(
                                "untilEpochMillis",
                                integerSchema("Window end (epoch millis, inclusive); after since, at most 24h apart."),
                            )
                        },
                    required = listOf("allowedPackages", "sinceEpochMillis", "untilEpochMillis"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_QUERIED, ST_PERMISSION_MISSING),
                                    "queried, or permission-missing (Notification Listener not enabled).",
                                ),
                            )
                            put("count", integerSchema("Number of entries returned."))
                            put(
                                "excludedCount",
                                integerSchema("In-window notifications filtered out by the allowlist."),
                            )
                            put(
                                "entries",
                                arraySchema(
                                    notificationEntrySchema(),
                                    "Bounded notification entries, newest first.",
                                ),
                            )
                            put("reason", stringSchema(128, "permission-missing on a refusal; empty on a query."))
                        },
                    required = listOf("status", "count", "excludedCount", "entries", "reason"),
                ),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 32_768,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: NotificationsBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "LongMethod")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val arr =
                    call.args["allowedPackages"] as? JsonArray
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: 'allowedPackages' must be an array",
                        )
                val packages = mutableListOf<String>()
                for (el in arr) {
                    val s =
                        (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: return ToolExecutorResult.Failed(
                                "invalid 'notifications.query' arguments: 'allowedPackages' entries must be strings",
                            )
                    if (s.isBlank() || s.length > MAX_PACKAGE_LEN) {
                        return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: each package must be 1..$MAX_PACKAGE_LEN",
                        )
                    }
                    packages.add(s)
                }
                if (packages.isEmpty() || packages.size > MAX_ALLOWED_PACKAGES) {
                    return ToolExecutorResult.Failed(
                        "invalid 'notifications.query' arguments: 'allowedPackages' must have " +
                            "1..$MAX_ALLOWED_PACKAGES entries",
                    )
                }
                val since =
                    intArg(call.args, "sinceEpochMillis")
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: 'sinceEpochMillis' must be an integer",
                        )
                val until =
                    intArg(call.args, "untilEpochMillis")
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: 'untilEpochMillis' must be an integer",
                        )
                if (until <= since) {
                    return ToolExecutorResult.Failed(
                        "invalid 'notifications.query' arguments: 'untilEpochMillis' must be after 'sinceEpochMillis'",
                    )
                }
                if (until - since > MAX_NOTIFICATION_WINDOW_MS) {
                    return ToolExecutorResult.Failed(
                        "invalid 'notifications.query' arguments: the time window may be at most 24h",
                    )
                }
                val out = bridge.query(NotificationQueryRequest(packages, since, until))
                val status =
                    when (out.status) {
                        NotificationQueryStatus.QUERIED -> ST_QUERIED
                        NotificationQueryStatus.PERMISSION_MISSING -> ST_PERMISSION_MISSING
                        NotificationQueryStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("count", JsonPrimitive(out.entries.size))
                        put("excludedCount", JsonPrimitive(out.excludedCount))
                        put(
                            "entries",
                            JsonArray(
                                out.entries.map { e ->
                                    buildJsonObject {
                                        put("packageName", JsonPrimitive(e.packageName))
                                        put("title", JsonPrimitive(e.title))
                                        put("text", JsonPrimitive(e.text))
                                        put("postedEpochMillis", JsonPrimitive(e.postedEpochMillis))
                                    }
                                },
                            ),
                        )
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: NotificationsBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// calendar.prepare_event — build + hold a structured draft (no write, no permission)
// ===========================================================================
object CalendarPrepareEventTool {
    const val NAME: String = "calendar.prepare_event"

    const val VERSION: Int = 1

    @Suppress("LongMethod") // model-facing descriptor kept as one readable block
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Prepare (draft) a calendar event from structured fields. Draft-first: this only builds " +
                    "and holds a structured draft for the user to review (title/time/timezone/location/" +
                    "notes); it never writes to the calendar and needs no permission. Returns a draftId " +
                    "to pass to calendar.commit_event.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("title", stringSchema(MAX_EVENT_TITLE, "The event title."))
                            put("startEpochMillis", integerSchema("Event start (epoch millis)."))
                            put("endEpochMillis", integerSchema("Event end (epoch millis); after start."))
                            put("location", stringSchema(MAX_EVENT_LOCATION, "Optional event location."))
                            put("notes", stringSchema(MAX_EVENT_NOTES, "Optional event notes/description."))
                            put(
                                "timeZoneId",
                                stringSchema(
                                    MAX_EVENT_TIMEZONE,
                                    "Optional IANA time-zone id; defaults to the device zone.",
                                ),
                            )
                        },
                    required = listOf("title", "startEpochMillis", "endEpochMillis"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_PREPARED, ST_INVALID),
                                    "prepared, or invalid (e.g. end not after start).",
                                ),
                            )
                            put("draftId", stringSchema(MAX_DRAFT_ID, "The held draft's id (empty when not prepared)."))
                            put("title", stringSchema(MAX_EVENT_TITLE, "The event title."))
                            put("startEpochMillis", integerSchema("Event start (epoch millis)."))
                            put("endEpochMillis", integerSchema("Event end (epoch millis)."))
                            put("location", stringSchema(MAX_EVENT_LOCATION, "The event location."))
                            put("notes", stringSchema(MAX_EVENT_NOTES, "The event notes."))
                            put("timeZoneId", stringSchema(MAX_EVENT_TIMEZONE, "The resolved time-zone id."))
                            put("reason", stringSchema(128, "Invalid reason; empty when prepared."))
                        },
                    required =
                        listOf(
                            "status",
                            "draftId",
                            "title",
                            "startEpochMillis",
                            "endEpochMillis",
                            "location",
                            "notes",
                            "timeZoneId",
                            "reason",
                        ),
                ),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 8192,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: CalendarBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val title =
                    strArg(call.args, "title", MAX_EVENT_TITLE)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'calendar.prepare_event' arguments: 'title' must be a non-empty string",
                        )
                val start =
                    intArg(call.args, "startEpochMillis")
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'calendar.prepare_event' arguments: 'startEpochMillis' must be an integer",
                        )
                val end =
                    intArg(call.args, "endEpochMillis")
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'calendar.prepare_event' arguments: 'endEpochMillis' must be an integer",
                        )
                val location = (call.args["location"] as? JsonPrimitive)?.content.orEmpty()
                val notes = (call.args["notes"] as? JsonPrimitive)?.content.orEmpty()
                val timeZoneId = (call.args["timeZoneId"] as? JsonPrimitive)?.content.orEmpty()
                val out = bridge.prepareEvent(CalendarEventRequest(title, start, end, location, notes, timeZoneId))
                val status =
                    when (out.status) {
                        CalendarPrepareStatus.PREPARED -> ST_PREPARED
                        CalendarPrepareStatus.INVALID -> ST_INVALID
                        CalendarPrepareStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("draftId", JsonPrimitive(out.draftId))
                        put("title", JsonPrimitive(out.title))
                        put("startEpochMillis", JsonPrimitive(out.startEpochMillis))
                        put("endEpochMillis", JsonPrimitive(out.endEpochMillis))
                        put("location", JsonPrimitive(out.location))
                        put("notes", JsonPrimitive(out.notes))
                        put("timeZoneId", JsonPrimitive(out.timeZoneId))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: CalendarBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// calendar.commit_event — the single write step (WRITE_CALENDAR-gated, L2, every call approved)
// ===========================================================================
object CalendarCommitEventTool {
    const val NAME: String = "calendar.commit_event"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Commit a previously prepared calendar-event draft (calendar.prepare_event) to the " +
                    "system Calendar Provider. This is the write step: it needs the WRITE_CALENDAR " +
                    "permission and is approved on every call (L2). Refused with status " +
                    "'permission-missing' when the permission is not granted, 'draft-not-found' when the " +
                    "draftId is not a held draft, or 'no-handler' when no writable calendar exists.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "draftId",
                                stringSchema(MAX_DRAFT_ID, "The draftId returned by calendar.prepare_event."),
                            )
                        },
                    required = listOf("draftId"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_COMMITTED, ST_PERMISSION_MISSING, ST_DRAFT_NOT_FOUND, ST_NO_HANDLER),
                                    "committed, permission-missing (WRITE_CALENDAR not granted), draft-not-found, " +
                                        "or no-handler (no writable calendar).",
                                ),
                            )
                            put("draftId", stringSchema(MAX_DRAFT_ID, "The draftId that was committed (or requested)."))
                            put(
                                "eventId",
                                stringSchema(MAX_EVENT_ID, "The new calendar event id (empty unless committed)."),
                            )
                            put("reason", stringSchema(128, "Stable note; empty on a commit."))
                        },
                    required = listOf("status", "draftId", "eventId", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: CalendarBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val draftId =
                    strArg(call.args, "draftId", MAX_DRAFT_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'calendar.commit_event' arguments: 'draftId' must be a non-empty string",
                        )
                val out = bridge.commitEvent(draftId)
                val status =
                    when (out.status) {
                        CalendarCommitStatus.COMMITTED -> ST_COMMITTED
                        CalendarCommitStatus.PERMISSION_MISSING -> ST_PERMISSION_MISSING
                        CalendarCommitStatus.DRAFT_NOT_FOUND -> ST_DRAFT_NOT_FOUND
                        CalendarCommitStatus.NO_HANDLER -> ST_NO_HANDLER
                        CalendarCommitStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("draftId", JsonPrimitive(out.draftId))
                        put("eventId", JsonPrimitive(out.eventId))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: CalendarBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

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
