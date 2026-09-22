package com.helix.core.storage.repository

import com.helix.core.model.AgentMode
import com.helix.core.storage.entity.SessionInputEntity

internal object SessionInputValidation {
    fun validate(spec: SessionInputSpec) {
        listOf(spec.inputId, spec.sessionId, spec.configuration.providerId).forEach(::identifier)
        spec.expectedTurnId?.let(::identifier)
        require(spec.revision >= 0 && spec.createdAt >= 0)
        require(spec.text.length <= SessionInputRepository.MAX_TEXT_CHARS && '\u0000' !in spec.text)
        require(spec.text.isNotBlank() || spec.attachments.isNotEmpty())
        require(spec.configuration.modelId.isNotBlank() && spec.configuration.modelId.length <= 256)
        require(spec.configuration.fingerprint.isNotBlank() && spec.configuration.fingerprint.length <= 1024)
        AgentMode.valueOf(spec.configuration.mode)
        require(spec.delivery != SessionInputDelivery.STEER || spec.expectedTurnId != null)
        require(spec.attachments.size <= MessageAttachmentRepository.MAX_ATTACHMENTS_PER_MESSAGE)
        require(
            spec.attachments
                .map { it.artifactId }
                .distinct()
                .size == spec.attachments.size,
        )
        spec.attachments.forEach {
            identifier(it.artifactId)
            require(it.boundSha256.length == 64 && it.boundSha256.all { char -> char in "0123456789abcdef" })
        }
    }

    fun park(
        reason: String,
        at: Long,
    ) {
        require(at >= 0)
        require(reason.length in 1..128 && reason.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
    }

    fun textBytes(spec: SessionInputSpec): Long =
        spec.text
            .toByteArray(Charsets.UTF_8)
            .size
            .toLong()

    fun entity(
        spec: SessionInputSpec,
        sequence: Long,
        textRef: String,
    ) = SessionInputEntity(
        inputId = spec.inputId,
        schemaVersion = 1,
        sessionId = spec.sessionId,
        sequence = sequence,
        delivery = spec.delivery.name,
        expectedTurnId = spec.expectedTurnId,
        revision = spec.revision,
        textRef = textRef,
        textBytes = textBytes(spec),
        providerId = spec.configuration.providerId,
        modelId = spec.configuration.modelId,
        mode = spec.configuration.mode,
        configurationFingerprint = spec.configuration.fingerprint,
        state = SessionInputState.PENDING.name,
        consumedTurnId = null,
        messageId = null,
        requestModelCallId = null,
        blockedReason = null,
        createdAt = spec.createdAt,
        updatedAt = spec.createdAt,
    )

    private fun identifier(value: String) {
        require(value.isNotBlank() && value.length <= 256 && value.none { it.code < 0x20 })
    }
}
