package com.helix.app.proot

import android.content.Context
import android.os.ParcelFileDescriptor
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.client.ProotResultClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Original-result collection; output effects must pass the host's current authorization before import. */
internal class DetachedJobCollection(
    context: Context,
    private val storage: HelixStorage,
    private val ownership: ExecutionOwnership,
    private val applyOutput: (DetachedJobBinding, ProotJobRecord, File?) -> Unit,
    private val settleBudget: (DetachedJobBinding, ProotJobRecord) -> Unit,
) {
    private val bindings = ProotJobBindingStore(storage)
    private val observations = DetachedJobObservationStore(storage)
    private val jobs = DetachedJobClient(context)
    private val results = ProotResultClient(ProotRuntimeSupervisor(context))
    private val store =
        ProotResultStore(
            storage,
            File(context.filesDir, "workspaces/app"),
            File(context.cacheDir, "proot-results"),
        )

    fun executor(): ToolExecutor =
        ownership.controlExecutor(
            resolve = { binding(it).owner() },
            execute = { call, permit -> collect(call, permit) },
        )

    @Suppress("ReturnCount") // Unavailable, unsettled and cancelled work must retain its original owner.
    private fun collect(
        call: ExecutableToolCall,
        permit: ExecutionOwnership.ReconciliationPermit?,
    ): ToolExecutorResult {
        val binding = binding(call)
        val record =
            jobs.query(binding).record
                ?: return failure("JOB_RESULT_UNAVAILABLE: query the original job again.")
        check(record.jobId == binding.jobId && record.executionId == binding.executionId)
        check(record.inputManifestSha256 == binding.inputManifestSha256)
        observations.observe(binding, record)
        if (record.state == ProotJobState.ORPHANED) {
            // Unknown execution consumes its original lease once; it never proves the process stopped.
            // An active Turn still owns its shared heartbeat; do not complete that live clock as proven stopped.
            if (TurnState.valueOf(storage.turns.resolve(binding.turnId).state).isTerminal) {
                settleBudget(binding, record)
            }
            return failure("JOB_REQUIRES_REVIEW: execution interrupted; original ownership remains retained.")
        }
        if (!record.state.isTerminal) {
            return failure("JOB_NOT_SETTLEABLE: the original execution is running.")
        }
        if (!hasReceipt(binding, record)) {
            val archive = if (record.state == ProotJobState.SUCCEEDED) archive(binding, record) else null
            if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
            // The host resolves the original output target and current authorization; no caller-selected replacement.
            applyOutput(binding, record, archive)
            recordReceipt(binding, record)
        }
        // A prior output receipt never skips a failed or interrupted Goal settlement.
        settleBudget(binding, record)
        val receipt =
            try {
                if (record.evidenceExpired) null else results.acknowledge(record)
            } catch (_: android.os.RemoteException) {
                null
            }
        receipt?.let { check(it.terminalCommit == record.terminalCommit && it.reconciledAtEpochMs != null) }
        check(permit == null || permit.settle()) { "original execution ownership changed" }
        observations.settled(binding, record)
        return ToolExecutorResult.Completed(
            buildJsonObject {
                put("originalCallId", binding.toolCallId)
                put("jobId", record.jobId)
                put("state", record.state.wire)
                put("exitCode", record.exitCode)
                put("terminalCommit", record.terminalCommit)
                put("settlementPending", false)
                put("acknowledged", receipt != null)
                put("resultAvailable", record.state == ProotJobState.SUCCEEDED)
            },
        )
    }

    private fun archive(
        binding: DetachedJobBinding,
        record: ProotJobRecord,
    ): File {
        store.readLocal(binding.turnId, binding.toolCallId)?.let { local ->
            return local.inputStream().use { store.persist(binding.turnId, binding.toolCallId, record, it) }
        }
        val fetched = requireNotNull(results.fetch(record)) { "Original result archive unavailable; nothing replayed" }
        fetched.use {
            check(it.record.terminalCommit == record.terminalCommit)
            ParcelFileDescriptor.AutoCloseInputStream(it.descriptor).use { input ->
                return store.persist(binding.turnId, binding.toolCallId, record, input)
            }
        }
    }

    private fun hasReceipt(
        binding: DetachedJobBinding,
        record: ProotJobRecord,
    ): Boolean {
        val event =
            try {
                storage.auditEvents.resolve(receiptId(binding))
            } catch (_: IllegalArgumentException) {
                return false
            }
        check(event.correlationId == binding.sessionId && event.type == "proot.job_collected")
        val payload = Json.parseToJsonElement(event.redactedPayload).jsonObject
        check(payload.getValue("terminalCommit").jsonPrimitive.content == record.terminalCommit)
        return true
    }

    private fun recordReceipt(
        binding: DetachedJobBinding,
        record: ProotJobRecord,
    ) {
        storage.auditEvents.append(
            receiptId(binding),
            binding.sessionId,
            "proot.job_collected",
            "platform",
            buildJsonObject {
                put("jobId", binding.jobId)
                put("toolCallId", binding.toolCallId)
                put("terminalCommit", record.terminalCommit)
            }.toString(),
            System.currentTimeMillis(),
        )
    }

    private fun binding(call: ExecutableToolCall): DetachedJobBinding =
        bindings.resolveDetached(
            requireNotNull(call.sessionId),
            call.args
                .getValue("originalCallId")
                .jsonPrimitive.content,
        )

    private fun DetachedJobBinding.owner() = ExecutionOwnership.Owner(executionId, jobId)

    private fun receiptId(binding: DetachedJobBinding) = "proot-collected-${binding.toolCallId}"

    private fun failure(reason: String) = ToolExecutorResult.Failed(reason, sideEffectFree = true)
}
