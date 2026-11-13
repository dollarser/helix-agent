package com.helix.core.model.input

/** Delivery is chosen by a trusted user entry point, never inferred from model content. */
enum class SessionInputDelivery { QUEUE, STEER }

data class InputAttachment(
    val artifactId: String,
    val boundSha256: String,
)

/** Opaque fingerprint binds the exact configuration checked by the application. No credentials. */
data class InputConfiguration(
    val providerId: String,
    val modelId: String,
    val mode: String,
    val fingerprint: String,
)

data class SessionInputSpec(
    val inputId: String,
    val sessionId: String,
    val delivery: SessionInputDelivery,
    val expectedTurnId: String?,
    val revision: Long,
    val text: String,
    val attachments: List<InputAttachment>,
    val configuration: InputConfiguration,
    val createdAt: Long,
    val reference: ConversationReferenceSnapshotInput? = null,
    val skillActivations: List<InputSkillActivation> = emptyList(),
    val knowledgeReferences: List<KnowledgeReference> = emptyList(),
    val delegation: ModelDelegationOrigin? = null,
)
