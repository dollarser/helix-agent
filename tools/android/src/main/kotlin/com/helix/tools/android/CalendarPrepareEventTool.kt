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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

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
                    notificationsCalendarToolsIntArg(call.args, "startEpochMillis")
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'calendar.prepare_event' arguments: 'startEpochMillis' must be an integer",
                        )
                val end =
                    notificationsCalendarToolsIntArg(call.args, "endEpochMillis")
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
