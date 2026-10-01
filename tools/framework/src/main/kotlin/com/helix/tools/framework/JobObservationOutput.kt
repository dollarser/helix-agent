package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

internal data class SeenJobObservation(
    val value: JobObservation,
    val receivedNanos: Long,
)

/** Emits bounded facts only. Missing observations remain explicitly unavailable, never invented RUNNING states. */
internal object JobObservationOutput {
    fun cancelled() =
        ToolExecutorResult.CancelledWithEffectTruth(
            "JOB_WAIT_CANCELLED: stopped observing; the original execution was not cancelled.",
            sideEffectFree = true,
            requiresReview = false,
        )

    fun timedOut() =
        ToolExecutorResult.TimedOutWithEffectTruth(
            "JOB_WAIT_DEADLINE: observation deadline expired; the original execution is unchanged.",
            sideEffectFree = true,
            requiresReview = false,
        )

    fun revoked() =
        ToolExecutorResult.CancelledWithEffectTruth(
            "JOB_OBSERVATION_REVOKED: permission or original binding changed; no execution was replayed.",
            sideEffectFree = true,
            requiresReview = false,
        )

    fun result(
        reason: JobObservationReason,
        bindings: List<JobObservationBinding>,
        known: Map<JobObservationBinding, SeenJobObservation>,
        nowNanos: Long,
        freshnessMillis: Long,
        waiting: Boolean,
    ): ToolExecutorResult.Completed {
        val items =
            bindings.map { binding ->
                val seen = known[binding]
                val stale =
                    reason == JobObservationReason.SOURCE_UNAVAILABLE || seen == null ||
                        nowNanos - seen.receivedNanos >= TimeUnit.MILLISECONDS.toNanos(freshnessMillis)
                item(binding, seen?.value, stale)
            }
        val output =
            buildJsonObject {
                put("reason", reason.name)
                put("observations", JsonArray(items))
                if (!waiting && bindings.size == 1) {
                    val binding = bindings.single()
                    val observed = known[binding]?.value
                    put("originalCallId", binding.handle)
                    put("jobId", binding.generation)
                    put("state", observed?.state)
                    put("terminal", observed?.terminal)
                    put("exitCode", observed?.exitCode)
                    put("elapsedDurationMs", observed?.elapsedDurationMillis)
                    put("terminalCommit", observed?.revision?.takeIf { observed.terminal })
                    put("settlementPending", observed?.settlementPending)
                }
            }
        return ToolExecutorResult.Completed(output)
    }

    private fun item(
        binding: JobObservationBinding,
        observation: JobObservation?,
        stale: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("handle", binding.handle)
            put("available", observation != null)
            put("stale", stale)
            put("state", observation?.state)
            put("terminal", observation?.terminal)
            put("requiresReview", observation?.requiresReview)
            put("settlementPending", observation?.settlementPending)
            put("revision", observation?.revision)
            put("observedAtMillis", observation?.observedAtMillis)
            put("exitCode", observation?.exitCode)
        }

    fun busy(): ToolExecutorResult.Completed =
        ToolExecutorResult.Completed(
            buildJsonObject {
                put("reason", JobObservationReason.OBSERVATION_BUSY.name)
                put("observations", JsonArray(emptyList()))
            },
        )
}
