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
                                    listOf(
                                        ST_COMMITTED,
                                        ST_PERMISSION_MISSING,
                                        ST_DRAFT_NOT_FOUND,
                                        CALENDAR_NO_HANDLER,
                                    ),
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
                        CalendarCommitStatus.NO_HANDLER -> CALENDAR_NO_HANDLER
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
