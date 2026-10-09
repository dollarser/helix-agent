package com.helix.app.proot

import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Waiting changes the reply timing only. The Job already has its approved detached owner and lease. */
internal object DetachedJobWait {
    fun millis(
        call: ExecutableToolCall,
        launched: ToolExecutorResult.Completed,
    ): Long? {
        val accepted = ((launched.output as? JsonObject)?.get("accepted") as? JsonPrimitive)?.content == "true"
        if (!accepted || (call.args["waitMode"] as? JsonPrimitive)?.content != "AUTO") return null
        return (call.args["waitMillis"] as? JsonPrimitive)?.longOrNull ?: 10_000
    }

    @Suppress("ReturnCount") // Keep terminal collection, cancellation and still-running acceptance distinct.
    fun finish(
        collection: ToolExecutor,
        call: ExecutableToolCall,
        launched: ToolExecutorResult.Completed,
        observation: ToolExecutorResult,
    ): ToolExecutorResult {
        val observed = (observation as? ToolExecutorResult.Completed)?.output as? JsonObject
        val reason = (observed?.get("reason") as? JsonPrimitive)?.content
        if (reason == "CONDITION_MET" && !call.cancel.isCancelled()) {
            val collected =
                collection.execute(
                    call.copy(args = buildJsonObject { put("originalCallId", call.toolCallId) }),
                )
            if (collected is ToolExecutorResult.Completed) {
                return collected.copy(
                    output =
                        JsonObject(
                            requireNotNull(collected.output as? JsonObject) +
                                buildJsonObject {
                                    put("accepted", true)
                                    put("executionComplete", true)
                                    put("waitReason", reason)
                                },
                        ),
                )
            }
            return when (collected) {
                is ToolExecutorResult.Failed -> {
                    collected.copy(sideEffectFree = false)
                }

                else -> {
                    ToolExecutorResult.CancelledWithEffectTruth(
                        "JOB_COLLECTION_UNSETTLED: inspect the original execution; nothing was replayed.",
                        sideEffectFree = false,
                        requiresReview = true,
                    )
                }
            }
        }
        if (call.cancel.isCancelled()) {
            return ToolExecutorResult.CancelledWithEffectTruth(
                "JOB_WAIT_CANCELLED: the approved original Job remains queryable; nothing was replayed.",
                sideEffectFree = false,
                requiresReview = false,
            )
        }
        return launched.copy(
            output =
                JsonObject(
                    requireNotNull(launched.output as? JsonObject) +
                        buildJsonObject {
                            put("waitReason", reason ?: "WAIT_RELEASED")
                            put("settlementPending", true)
                        },
                ),
        )
    }
}
