package com.helix.feature.conversation.application.input

import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.turn.TurnInputAttachment
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.model.input.InputAttachment
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.InputSkillActivation
import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.SessionInputDelivery

data class InputDeliveryKey(
    val inputId: String,
    val sessionId: String,
    val revision: Long,
)

/** The accepted content's admission facts. No Room row, ordering, or filesystem content reference. */
@Suppress("LongParameterList") // Exact accepted admission facts, without persisted implementation fields.
data class AcceptedInputSnapshot(
    val key: InputDeliveryKey,
    val delivery: SessionInputDelivery,
    val expectedTurnId: String?,
    val configuration: InputConfiguration,
    val attachments: List<InputAttachment>,
    val hasImages: Boolean,
    val reference: ConversationReferenceSnapshotInput?,
    val skillActivations: List<InputSkillActivation>,
    val knowledgeReferences: List<KnowledgeReference>,
    val originalProviderSnapshot: String?,
)

data class InputSessionSelection(
    val providerId: String?,
    val modelId: String?,
)

data class PreparedSessionInput(
    val content: String,
    val attachments: List<TurnInputAttachment>,
)

interface SessionInputDeliveryStore {
    fun load(key: InputDeliveryKey): AcceptedInputSnapshot?

    fun selection(sessionId: String): InputSessionSelection?

    fun headSteer(
        sessionId: String,
        turnId: String,
    ): InputDeliveryKey?

    fun steerProviderSnapshot(
        sessionId: String,
        turnId: String,
    ): String?

    fun park(
        key: InputDeliveryKey,
        reason: String,
        at: Long,
    )

    fun parkAdmissionFailureIfIdle(
        key: InputDeliveryKey,
        at: Long,
    )
}

interface InputDeliveryProvider {
    fun selectable(providerId: String): Boolean

    suspend fun defaultModel(providerId: String): String

    suspend fun supportsVision(
        providerId: String,
        modelId: String?,
    ): Boolean

    suspend fun supportsTools(
        providerId: String,
        modelId: String?,
    ): Boolean

    suspend fun snapshot(
        providerId: String,
        modelId: String?,
    ): String
}

/** Existing runtime/admission owner; implementations must retain its closed admission CAS. */
interface InputDeliveryRuntime {
    fun hasLiveTurn(sessionId: String): Boolean

    fun selectedControl(sessionId: String): RunControlConfig

    fun steerControl(turnId: String?): RunControlConfig?

    fun queueStillConsumable(
        key: InputDeliveryKey,
        requireHead: Boolean,
    ): Boolean

    suspend fun deliverQueued(
        key: InputDeliveryKey,
        prepared: PreparedSessionInput,
        control: RunControlConfig,
        requireHead: Boolean,
    ): String?
}
