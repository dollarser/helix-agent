package com.helix.app.application.agent

import com.helix.core.agent.RunControlConfig
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.model.input.InputConfiguration

/** Facts needed to prepare admission, never an alternative admission writer or execution owner. */
internal interface SubmissionAdmissionFacts {
    fun isDelegatedTurn(turnId: String): Boolean = false

    fun session(sessionId: String): SubmissionSession?

    fun retryText(
        sessionId: String,
        turnId: String,
    ): String?

    fun pendingInput(
        inputId: String,
        expectedRevision: Long,
    ): PendingSubmissionInput?

    fun hasQueuedInput(sessionId: String): Boolean

    fun queueStillConsumable(
        inputId: String,
        sessionId: String,
        expectedRevision: Long,
        requireHead: Boolean,
    ): Boolean

    fun markConfigurationChanged(
        input: PendingSubmissionInput,
        now: Long,
    )

    fun recoverySessionId(turnId: String): String

    fun recoveryGoalId(turnId: String): String?
}

internal data class SubmissionSession(
    val id: String,
    val providerId: String?,
    val modelId: String?,
    val archivedAt: Long?,
)

/** Provider reads suspend outside the execution monitor; credentials never enter this contract. */
internal fun interface SubmissionProviderSnapshots {
    suspend fun capture(
        providerId: String,
        modelId: String?,
    ): SubmissionProviderSnapshot
}

internal data class SubmissionProviderSnapshot(
    val encoded: String,
    val modelId: String,
)

/** Only admission facts; storage sequence, content references and consumption state stay in Room. */
internal data class PendingSubmissionInput(
    val inputId: String,
    val sessionId: String,
    val revision: Long,
    val configuration: InputConfiguration,
    val reference: ConversationReferenceSnapshotInput?,
    val delegation: com.helix.core.model.input.ModelDelegationOrigin? = null,
)
