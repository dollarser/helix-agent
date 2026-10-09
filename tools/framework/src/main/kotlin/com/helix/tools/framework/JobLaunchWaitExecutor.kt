package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host-wired launch followed by observation. The launch itself always uses the normal deadline/owner runner. */
internal class JobLaunchWaitExecutor(
    private val service: JobObservationService,
    private val launch: ToolExecutor,
    private val waitMillis: (ExecutableToolCall, ToolExecutorResult.Completed) -> Long?,
    private val finish: (ExecutableToolCall, ToolExecutorResult.Completed, ToolExecutorResult) -> ToolExecutorResult,
) : ToolExecutor {
    override fun execute(call: ExecutableToolCall): ToolExecutorResult = launch.execute(call)

    fun afterLaunch(
        call: ExecutableToolCall,
        launched: ToolExecutorResult,
        mayRead: () -> Boolean,
    ): JobObservationSubmission {
        val millis = (launched as? ToolExecutorResult.Completed)?.let { waitMillis(call, it) }
        return if (launched is ToolExecutorResult.Completed && millis != null) {
            observe(call, launched, millis, mayRead)
        } else {
            JobObservationSubmission.immediate(launched)
        }
    }

    private fun observe(
        call: ExecutableToolCall,
        launched: ToolExecutorResult.Completed,
        millis: Long,
        mayRead: () -> Boolean,
    ): JobObservationSubmission {
        require(millis in 250..15_000)
        val handle = (launched.output as? JsonObject)?.get("originalCallId") as? JsonPrimitive
        require(handle?.content == call.toolCallId) { "JOB_LAUNCH_HANDLE_CHANGED" }
        val observing =
            call.copy(
                args =
                    buildJsonObject {
                        put("handles", JsonArray(listOf(requireNotNull(handle))))
                        put("condition", "ALL")
                    },
                deadline = minOf(call.deadline, service.now().plusMillis(millis + 250)),
            )
        val submission = service.start(observing, true, mayRead)
        return JobObservationPublication.map(submission) { observed -> finish(call, launched, observed) }
    }
}
