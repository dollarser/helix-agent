package com.helix.app.proot

import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Host disposition of missing evidence, never a fabricated Runtime terminal or output receipt. */
internal class DetachedJobMissingCollection(
    private val storage: HelixStorage,
    private val currentBoot: () -> Int?,
    private val settleBudget: (DetachedJobBinding) -> Unit,
) {
    @Suppress("ReturnCount") // Every uncertain or cancelled path retains the original owner.
    fun collect(
        binding: DetachedJobBinding,
        status: Byte,
        cancel: CancelSignal,
        permit: ExecutionOwnership.ReconciliationPermit?,
    ): ToolExecutorResult {
        if (status != ProotRuntimeProtocol.REPLY_JOB_NOT_FOUND) {
            return failed("JOB_RESULT_UNAVAILABLE: query the original job again.")
        }
        if (cancel.isCancelled()) return ToolExecutorResult.Cancelled
        val originalState = TurnState.valueOf(storage.turns.resolve(binding.turnId).state)
        if (!originalState.isTerminal && originalState != TurnState.INTERRUPTED) {
            return failed("JOB_ORIGINAL_TURN_ACTIVE: wait for the original Turn to finish.")
        }
        val original = ProotJobBindingStore(storage).resolve(binding.toolCallId)["bootCount"]?.jsonPrimitive?.intOrNull
        val boot = currentBoot()
        if (!DetachedJobBootProof.canSettle(storage, binding, original, boot)) {
            return failed(DetachedJobBootProof.reviewReason(boot))
        }
        settleBudget(binding)
        check(permit == null || permit.settle()) { "original execution ownership changed" }
        DetachedJobObservationStore(storage).disposed(binding, requireNotNull(boot))
        return ToolExecutorResult.Completed(
            buildJsonObject {
                put("originalCallId", binding.toolCallId)
                put("jobId", binding.jobId)
                put("state", "UNKNOWN")
                put("settlementPending", false)
                put("resultAvailable", false)
                put("evidenceMissing", true)
                put("acknowledged", false)
            },
        )
    }

    private fun failed(detail: String) = ToolExecutorResult.Failed(detail, sideEffectFree = true)
}
