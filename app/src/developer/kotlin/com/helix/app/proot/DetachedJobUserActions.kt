package com.helix.app.proot

import android.os.SystemClock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * Explicit USER recovery, not a model ToolCall. Only fixed original-call arguments reach the
 * shared host implementations; model entry points continue through the Dispatcher.
 */
internal class DetachedJobUserActions(
    private val storage: HelixStorage,
    private val ownership: ExecutionOwnership,
    private val executors: Map<BackgroundJobAction, ToolExecutor>,
) {
    fun perform(
        job: BackgroundJobUi,
        action: BackgroundJobAction,
        cancelled: () -> Boolean,
    ): BackgroundJobActionOutcome {
        val binding = ProotJobBindingStore(storage).resolveDetached(job.sessionId, job.callId)
        check(binding.turnId == job.turnId) { "Original Job turn changed" }
        check(storage.toolCalls.byTurnAndCallId(job.turnId, job.callId)?.name == DetachedJobTools.START)
        val id = UUID.randomUUID().toString()
        audit(id, job, action, "REQUESTED")
        val result =
            try {
                val call = call(id, job, action, cancelled)
                outcome(action, ownership.guard(executors.getValue(action)).execute(call))
            } catch (_: Exception) {
                BackgroundJobActionOutcome.FAILED
            }
        audit("$id-result", job, action, result.name)
        return result
    }

    private fun call(
        id: String,
        job: BackgroundJobUi,
        action: BackgroundJobAction,
        cancelled: () -> Boolean,
    ): ExecutableToolCall {
        val deadline = SystemClock.elapsedRealtime() + ACTION_LIMIT_MS
        val name =
            when (action) {
                BackgroundJobAction.QUERY -> DetachedJobTools.STATUS
                BackgroundJobAction.CANCEL -> DetachedJobTools.CANCEL
                BackgroundJobAction.COLLECT -> DetachedJobTools.COLLECT
            }
        return ExecutableToolCall(
            "user-job-$id",
            name,
            "1",
            buildJsonObject { put("originalCallId", job.callId) },
            ExecutionTargetType.LOCAL_PROOT,
            Instant.now().plusMillis(ACTION_LIMIT_MS),
            object : CancelSignal {
                override fun isCancelled() = cancelled() || SystemClock.elapsedRealtime() >= deadline
            },
            job.sessionId,
            job.turnId,
        )
    }

    private fun outcome(
        action: BackgroundJobAction,
        result: ToolExecutorResult,
    ): BackgroundJobActionOutcome {
        val output =
            (result as? ToolExecutorResult.Completed)?.output as? JsonObject
        if (output == null) {
            val detail = (result as? ToolExecutorResult.Failed)?.detail.orEmpty()
            return when {
                detail.startsWith("JOB_REQUIRES_REVIEW:") -> BackgroundJobActionOutcome.REVIEW_REQUIRED
                detail.startsWith("EXECUTION_BUSY:") -> BackgroundJobActionOutcome.BUSY
                else -> BackgroundJobActionOutcome.FAILED
            }
        }
        val state = (output["state"] as? JsonPrimitive)?.content
        return when {
            action == BackgroundJobAction.COLLECT &&
                (output["settlementPending"] as? JsonPrimitive)?.content == "false" -> {
                BackgroundJobActionOutcome.SETTLED
            }

            state == "ORPHANED" -> {
                BackgroundJobActionOutcome.REVIEW_REQUIRED
            }

            state == null -> {
                BackgroundJobActionOutcome.FAILED
            }

            state !in setOf("RUNNING", "PENDING") -> {
                BackgroundJobActionOutcome.TERMINAL_PENDING
            }

            action == BackgroundJobAction.CANCEL -> {
                BackgroundJobActionOutcome.STOP_REQUESTED
            }

            else -> {
                BackgroundJobActionOutcome.ACTIVE
            }
        }
    }

    private fun audit(
        id: String,
        job: BackgroundJobUi,
        action: BackgroundJobAction,
        result: String,
    ) {
        storage.auditEvents.append(
            "user-job-$id",
            job.sessionId,
            "proot.job_user_action",
            "user",
            buildJsonObject {
                put("toolCallId", job.callId)
                put("turnId", job.turnId)
                put("action", action.name)
                put("result", result)
            }.toString(),
            System.currentTimeMillis(),
        )
    }

    private companion object {
        const val ACTION_LIMIT_MS = 120_000L
    }
}
