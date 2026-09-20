package com.helix.runtime.proot.core

/** Host-selected manual session identity. No ToolCall/Turn or model-owned permission is invented. */
data class PtySessionOrigin(
    val sessionId: String,
    val generation: String,
    val executionId: String,
    val workspace: String,
    val runtimeGeneration: String,
    val bootCount: Int?,
    val createdAtEpochMs: Long,
    val startedAtElapsedMs: Long,
    val deadlineElapsedMs: Long,
) {
    init {
        listOf(sessionId, generation, executionId, runtimeGeneration).forEach {
            require(it.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        }
        require(workspace.startsWith('/') && workspace.length <= 4096 && workspace.none(Char::isISOControl))
        require(bootCount == null || bootCount >= 0)
        require(createdAtEpochMs >= 0 && startedAtElapsedMs >= 0 && deadlineElapsedMs > startedAtElapsedMs)
    }
}

/** PID alone is not durable identity. Matching start ticks is required before acting on a recovered PID. */
data class PtyProcessIdentity(
    val pid: Int,
    val startTicks: Long,
) {
    init {
        require(pid > 1 && startTicks >= 0)
    }
}

/**
 * Runtime's durable facts. STARTING is written before fork and may already have side effects after a
 * crash. UNKNOWN is not stopped. A signal receipt, EOF, or leader exit is not a process-tree proof.
 * This record does not authorize execution or release the host's retained execution admission.
 */
data class PtySessionRecord(
    val origin: PtySessionOrigin,
    val phase: Phase = Phase.STARTING,
    val process: PtyProcessIdentity? = null,
    val stopReason: StopReason? = null,
    val stopProof: StopProof? = null,
    val exitStatus: Int? = null,
    val reconciled: Boolean = false,
    val stoppedAtBootCount: Int? = null,
) {
    enum class Phase { STARTING, RUNNING, CLOSING, STOPPED, UNKNOWN }

    enum class StopReason { USER, LEASE_EXPIRED, IDLE, IO_FAILURE, RUNTIME_LOST, START_FAILED, SHELL_EXIT }

    enum class StopProof { NEVER_STARTED, PROCESS_TREE_EXIT, DEVICE_REBOOT }

    init {
        require(exitStatus == null || exitStatus in 0..511)
        require(phase != Phase.STARTING || (process == null && stopReason == null))
        require(phase != Phase.RUNNING || (process != null && stopReason == null))
        require(phase !in setOf(Phase.CLOSING, Phase.STOPPED, Phase.UNKNOWN) || stopReason != null)
        require(
            (stopProof != null) ==
                (phase == Phase.STOPPED || (phase == Phase.UNKNOWN && stopProof == StopProof.DEVICE_REBOOT)),
        )
        require(stopProof != StopProof.NEVER_STARTED || process == null)
        require(stopProof != StopProof.PROCESS_TREE_EXIT || process != null)
        require(stopProof != StopProof.DEVICE_REBOOT || phase == Phase.UNKNOWN)
        require(exitStatus == null || stopProof == StopProof.PROCESS_TREE_EXIT)
        require(!reconciled || stopProof != null)
        require((stoppedAtBootCount != null) == (stopProof == StopProof.DEVICE_REBOOT))
        if (stoppedAtBootCount != null) require(origin.bootCount != null && stoppedAtBootCount > origin.bootCount)
    }

    fun started(identity: PtyProcessIdentity): PtySessionRecord {
        check(phase in setOf(Phase.STARTING, Phase.CLOSING) && process == null)
        return copy(phase = if (phase == Phase.CLOSING) phase else Phase.RUNNING, process = identity)
    }

    fun requestStop(reason: StopReason): PtySessionRecord {
        require(reason in setOf(StopReason.USER, StopReason.LEASE_EXPIRED, StopReason.IDLE, StopReason.IO_FAILURE))
        return if (phase in
            setOf(Phase.STARTING, Phase.RUNNING)
        ) {
            copy(phase = Phase.CLOSING, stopReason = reason)
        } else {
            this
        }
    }

    /** Use only when the live launcher definitively knows fork never happened, not during recovery. */
    fun neverStarted(): PtySessionRecord {
        check(phase == Phase.STARTING || (phase == Phase.CLOSING && process == null))
        return copy(
            phase = Phase.STOPPED,
            stopReason = stopReason ?: StopReason.START_FAILED,
            stopProof = StopProof.NEVER_STARTED,
        )
    }

    /** Caller must prove all owned jobs ended; observing the initial leader alone is insufficient. */
    fun stoppedTree(status: Int): PtySessionRecord {
        check(phase in setOf(Phase.RUNNING, Phase.CLOSING) && process != null)
        return copy(
            phase = Phase.STOPPED,
            stopReason = stopReason ?: StopReason.SHELL_EXIT,
            stopProof = StopProof.PROCESS_TREE_EXIT,
            exitStatus = status,
        )
    }

    fun runtimeLost(): PtySessionRecord =
        if (stopProof != null || phase == Phase.UNKNOWN) {
            this
        } else {
            copy(phase = Phase.UNKNOWN, stopReason = stopReason ?: StopReason.RUNTIME_LOST)
        }

    /** Explicit same-identity recovery only. Missing/equal/backward boot evidence proves nothing. */
    fun afterReboot(currentBootCount: Int?): PtySessionRecord {
        check(phase == Phase.UNKNOWN)
        val original = origin.bootCount
        check(original != null && currentBootCount != null && currentBootCount > original)
        return copy(stopProof = StopProof.DEVICE_REBOOT, stoppedAtBootCount = currentBootCount)
    }

    fun acknowledge(): PtySessionRecord {
        check(stopProof != null)
        return copy(reconciled = true)
    }
}
