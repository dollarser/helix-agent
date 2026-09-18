package com.helix.app.proot

import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Bound control only: no command replay, output import, lease renewal, or automatic owner release. */
internal class DetachedJobControl(
    private val resolve: (String, String) -> DetachedJobBinding,
    private val query: (DetachedJobBinding) -> DetachedJobClient.Reply,
    private val cancel: (DetachedJobBinding) -> DetachedJobClient.Reply,
    private val ownership: ExecutionOwnership,
) {
    fun executor(stop: Boolean): ToolExecutor =
        ownership.controlExecutor(
            resolve = { call -> binding(call).owner() },
            execute = { call, _ ->
                val binding = binding(call)
                val reply = if (stop) cancel(binding) else query(binding)
                val record = reply.record
                if (record == null) {
                    ToolExecutorResult.Failed(
                        "JOB_CONTROL_UNAVAILABLE: query the original call again; nothing was replayed.",
                        sideEffectFree = !stop,
                        requiresReview = stop,
                    )
                } else {
                    check(binding.matches(record)) { "Runtime returned another job" }
                    ToolExecutorResult.Completed(
                        buildJsonObject {
                            put("originalCallId", binding.toolCallId)
                            put("jobId", record.jobId)
                            put("state", record.state.wire)
                            put("terminal", record.state.isTerminal)
                            put("exitCode", record.exitCode)
                            put("elapsedDurationMs", record.elapsedDurationMs)
                            put("terminalCommit", record.terminalCommit)
                            // A terminal process does not prove that host effects/imports have settled.
                            put("settlementPending", ownership.retainedOwner() == binding.owner())
                        },
                    )
                }
            },
        )

    companion object {
        fun create(
            context: android.content.Context,
            storage: com.helix.core.storage.HelixStorage,
            ownership: ExecutionOwnership,
        ): DetachedJobControl {
            val bindings = ProotJobBindingStore(storage)
            val client = DetachedJobClient(context)
            return DetachedJobControl(bindings::resolveDetached, client::query, client::cancel, ownership)
        }
    }

    private fun binding(call: ExecutableToolCall): DetachedJobBinding {
        val session = requireNotNull(call.sessionId) { "missing trusted session" }
        val original = requireNotNull(call.args["originalCallId"]).jsonPrimitive.content
        return resolve(session, original).also {
            check(it.sessionId == session && it.toolCallId == original) { "original job binding mismatch" }
        }
    }
}

private fun DetachedJobBinding.owner() = ExecutionOwnership.Owner(executionId, jobId)

private fun DetachedJobBinding.matches(record: ProotJobRecord): Boolean =
    jobId == record.jobId && executionId == record.executionId && inputManifestSha256 == record.inputManifestSha256
