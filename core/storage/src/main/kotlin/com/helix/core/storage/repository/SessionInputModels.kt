package com.helix.core.storage.repository

/** Delivery is chosen by a trusted user entry point, never inferred from model content. */
enum class SessionInputDelivery { QUEUE, STEER }

enum class SessionInputState { PENDING, NEEDS_ATTENTION, APPENDED, WITHDRAWN }

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
)

/** Content remains in ContentStore; listing a queue never loads its bodies. */
data class SessionInputRecord(
    val schemaVersion: Int,
    val inputId: String,
    val sessionId: String,
    val sequence: Long,
    val delivery: SessionInputDelivery,
    val expectedTurnId: String?,
    val revision: Long,
    val textRef: String,
    val textBytes: Long,
    val attachments: List<InputAttachment>,
    val configuration: InputConfiguration,
    val state: SessionInputState,
    val consumedTurnId: String?,
    val messageId: String?,
    val requestModelCallId: String?,
    val blockedReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface SessionInputAcceptResult {
    data class Accepted(
        val record: SessionInputRecord,
        val duplicate: Boolean,
    ) : SessionInputAcceptResult

    data class Rejected(
        val reason: String,
    ) : SessionInputAcceptResult
}
