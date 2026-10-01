package com.helix.app.proot

import android.content.Context
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.tools.framework.JobObservation
import com.helix.tools.framework.JobObservationBinding
import com.helix.tools.framework.JobObservationPort

/** Read-only original-identity adapter. It never owns a reconciliation permit or calls cancel/submit/collect. */
internal class LinuxJobObservationPort(
    private val resolveOriginal: (String, String) -> DetachedJobBinding,
    private val queryOriginal: (DetachedJobBinding) -> ProotJobRecord?,
    private val observe: (DetachedJobBinding, ProotJobRecord) -> Unit,
    private val isSettled: (DetachedJobBinding) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : JobObservationPort {
    override fun resolve(
        sessionId: String,
        handle: String,
    ): JobObservationBinding = resolveOriginal(sessionId, handle).observationBinding()

    override fun isCurrent(binding: JobObservationBinding): Boolean =
        resolveOriginal(binding.sessionId, binding.handle).observationBinding() == binding

    override fun query(binding: JobObservationBinding): JobObservation? {
        val original = resolveOriginal(binding.sessionId, binding.handle)
        require(original.observationBinding() == binding) { "JOB_BINDING_CHANGED" }
        val record = queryOriginal(original) ?: return null
        require(
            original.jobId == record.jobId && original.executionId == record.executionId &&
                original.inputManifestSha256 == record.inputManifestSha256,
        ) { "JOB_REPLY_BINDING_CHANGED" }
        observe(original, record)
        return JobObservation(
            binding = binding,
            state = record.state.wire,
            terminal = record.state.isTerminal,
            requiresReview = record.state == ProotJobState.ORPHANED,
            settlementPending = !isSettled(original),
            revision = record.terminalCommit ?: record.state.wire,
            observedAtMillis = nowMillis(),
            exitCode = record.exitCode,
            elapsedDurationMillis = record.elapsedDurationMs,
        )
    }

    private fun DetachedJobBinding.observationBinding() =
        JobObservationBinding(
            sessionId,
            turnId,
            toolCallId,
            "android-proot",
            executionId,
            jobId,
            inputManifestSha256,
        )

    companion object {
        fun create(
            context: Context,
            storage: HelixStorage,
        ): LinuxJobObservationPort {
            val bindings = ProotJobBindingStore(storage)
            val client = DetachedJobClient(context)
            val receipts = DetachedJobObservationStore(storage)
            return LinuxJobObservationPort(
                bindings::resolveDetached,
                { client.query(it).record },
                receipts::observe,
                { receipts.read(it)?.settled == true },
            )
        }
    }
}
