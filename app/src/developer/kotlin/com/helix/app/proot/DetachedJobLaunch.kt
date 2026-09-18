package com.helix.app.proot

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.client.ProotEnvScreen
import com.helix.runtime.proot.core.DetachedLease
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** Starts one approved Job and returns acceptance, never execution success or an automatic continuation. */
internal class DetachedJobLaunch(
    private val client: DetachedJobClient,
    private val gate: () -> LinuxRuntimeGate,
    store: WorkspaceArtifactStore,
    private val scratchRoot: File,
    private val ownership: ExecutionOwnership,
    private val knownSecretValues: () -> Set<String>,
    private val recheck: (LinuxRunTool.ParsedLinuxCall) -> ToolExecutorResult?,
    /** Persist original identity and allocate the Goal lease, if any, before the wire. */
    private val prepare: (LinuxRunTool.ParsedLinuxCall, ProotJobSpec) -> Long,
    /** Durable no-start accounting must succeed before the retained execution is released. */
    private val reject: (LinuxRunTool.ParsedLinuxCall, ProotJobSpec) -> Unit,
) : LinuxRunTool.LinuxExecutor {
    private val snapshots = LinuxInputSnapshot(store)

    override fun execute(
        call: LinuxRunTool.ParsedLinuxCall,
        isCancelled: () -> Boolean,
    ): ToolExecutorResult {
        if (isCancelled()) return ToolExecutorResult.Cancelled
        val budget =
            RuntimeSubmissionBudget(call.deadlineEpochMs, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        val identity = UUID.randomUUID().toString().replace("-", "")
        val scratch = File(scratchRoot, "detached-$identity")
        check(scratch.mkdirs()) { "job scratch unavailable" }
        return try {
            launch(call, isCancelled, budget, scratch, identity)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Suppress("ReturnCount") // Every refusal before transfer is known not to have submitted work.
    private fun launch(
        call: LinuxRunTool.ParsedLinuxCall,
        isCancelled: () -> Boolean,
        budget: RuntimeSubmissionBudget,
        scratch: File,
        identity: String,
    ): ToolExecutorResult {
        if (gate() != LinuxRuntimeGate.READY) return failure("RUNTIME_UNAVAILABLE")
        val input = File(scratch, "input.zip")
        val hash = snapshots.build(call.inputReferences, input) ?: return failure("INPUT_BUILD_FAILED")
        val screened = ProotEnvScreen.screen(call.environment, knownSecretValues(), extraAllowedNames = emptySet())
        if (screened !is ProotEnvScreen.Verdict.Approved) return failure("ENV_REFUSED")
        if (isCancelled()) return ToolExecutorResult.Cancelled
        recheck(call)?.let { return it }
        val remaining = budget.remainingMillis(SystemClock.elapsedRealtime()).coerceAtMost(DetachedLease.MAX_MS)
        if (remaining < DetachedLease.MIN_MS) return failure("BUDGET_EXHAUSTED_BEFORE_SUBMIT")
        val spec =
            ProotJobSpec(
                executionId = "exec_$identity",
                jobId = "job_${identity.take(12)}",
                command = call.command,
                relativeWorkingDirectory = call.cwd,
                environment = screened.environment,
                deadlineMs = remaining,
                maxOutputBytes = 8L * 1024 * 1024,
                inputManifestSha256 = hash,
            )
        val binding =
            DetachedJobBinding(
                requireNotNull(call.sessionId),
                requireNotNull(call.turnId),
                call.toolCallId,
                spec.jobId,
                spec.executionId,
                hash,
            )
        val allocationStarted = SystemClock.elapsedRealtime()
        val allocated = prepare(call, spec)
        if (allocated < DetachedLease.MIN_MS) {
            reject(call, spec)
            return failure("BUDGET_EXHAUSTED_BEFORE_SUBMIT")
        }
        val owner = ExecutionOwnership.Owner(spec.executionId, spec.jobId)
        check(ownership.retainForCall(call.toolCallId, owner)) { "execution ownership transfer refused" }
        // Runtime keeps its own durable output archive. No host scratch PFD is needed after acceptance.
        val reply =
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { inputFd ->
                ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_WRITE_ONLY).use { outputFd ->
                    client.submit(
                        binding,
                        spec,
                        allocated,
                        inputFd,
                        outputFd,
                        remainingBudget = {
                            val now = SystemClock.elapsedRealtime()
                            minOf(budget.remainingMillis(now), allocated - (now - allocationStarted).coerceAtLeast(0))
                        },
                        isCancelled = isCancelled,
                    )
                }
            }
        return finish(call, spec, owner, reply)
    }

    private fun finish(
        call: LinuxRunTool.ParsedLinuxCall,
        spec: ProotJobSpec,
        owner: ExecutionOwnership.Owner,
        reply: DetachedJobClient.Reply,
    ): ToolExecutorResult =
        when (reply.status) {
            ProotRuntimeProtocol.REPLY_JOB_ACCEPTED, ProotRuntimeProtocol.REPLY_JOB_DUPLICATE -> {
                ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("accepted", true)
                        put("originalCallId", call.toolCallId)
                        put("jobId", spec.jobId)
                        put("state", requireNotNull(reply.record).state.wire)
                        put("executionComplete", false)
                    },
                )
            }

            ProotRuntimeProtocol.REPLY_JOB_REJECTED -> {
                if (reply.refusal !in NO_START_REFUSALS) {
                    unknown()
                } else {
                    reject(call, spec)
                    check(ownership.releaseUnsubmittedForCall(call.toolCallId, owner))
                    failure(reply.refusal ?: "JOB_REFUSED")
                }
            }

            else -> {
                unknown()
            }
        }

    companion object {
        private val NO_START_REFUSALS =
            setOf(
                "JOURNAL_FULL",
                "RUNTIME_NOT_READY",
                "BACKGROUND_UNAVAILABLE",
                "EXECUTION_BUSY",
                "CANCELLED_BEFORE_START",
                "CANCELLED_BEFORE_SUBMIT",
                "BUDGET_EXHAUSTED_BEFORE_SUBMIT",
            )
    }

    private fun unknown() =
        ToolExecutorResult.Failed(
            "SUBMISSION_UNKNOWN: query the original call; do not replay the command.",
            requiresReview = true,
        )

    private fun failure(code: String) = ToolExecutorResult.Failed(code, sideEffectFree = true)
}
