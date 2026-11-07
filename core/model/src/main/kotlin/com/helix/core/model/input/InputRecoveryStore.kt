package com.helix.core.model.input

/** Durable commands, not a transaction callback. Implementations share the executor's Stop gate. */
interface InputRecoveryStore {
    fun candidates(sessionId: String): List<RecoveryInputCandidate>

    /** Claims the exact parked revision and transfers a finished Steer in the same transaction. */
    fun claim(
        input: RecoveryInputCandidate,
        at: Long,
    ): InputRecoveryClaim

    /** Neither command may operate on a later revision or undo a persisted user Stop. */
    fun resume(
        input: RecoveryInputCandidate,
        at: Long,
    ): Boolean

    fun fail(
        input: RecoveryInputCandidate,
        reason: String,
        notice: String,
        at: Long,
    ): Boolean
}

sealed interface InputRecoveryClaim {
    data class Claimed(
        val input: RecoveryInputCandidate,
    ) : InputRecoveryClaim

    data object AlreadyClaimed : InputRecoveryClaim

    data object Unavailable : InputRecoveryClaim
}

object InputRecoveryPolicy {
    const val NOTICE_KIND = "INPUT_RECOVERY_NOTICE"

    fun recoverable(reason: String?): Boolean =
        reason in
            setOf(
                "PROCESS_INTERRUPTED",
                "STEER_TARGET_FINISHED",
                "INPUT_DELIVERY_FAILED",
                "INPUT_REVALIDATION_FAILED",
                "INPUT_CONFIGURATION_CHANGED",
                "INPUT_ADMISSION_FAILED",
                "TURN_NOT_COMPLETED",
                "TURN_NEEDS_REVIEW",
                "SESSION_NEEDS_ATTENTION",
            )
}

/** Only the identity/version and scheduling facts needed to recover one accepted intent. */
data class RecoveryInputCandidate(
    val inputId: String,
    val sessionId: String,
    val revision: Long,
    val delivery: SessionInputDelivery,
    val expectedTurnId: String?,
    val blockedReason: String?,
)
