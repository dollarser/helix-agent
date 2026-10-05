package com.helix.app.chat

import com.helix.core.agent.RunControlConfig
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.InputConfiguration
import com.helix.core.storage.repository.SessionInputRecord
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Binds producer identity independently of normalized image artifact identities. Contains no secrets. */
internal object SessionInputBinding {
    fun configuration(
        request: ChatSubmission,
        providerId: String,
        modelId: String,
        providerFacts: String,
        control: RunControlConfig,
        selectedMode: String,
    ): InputConfiguration =
        InputConfiguration(
            providerId,
            modelId,
            control.mode.name,
            "v1:${configurationHash(providerFacts, control, selectedMode)}:${intentHash(request)}",
        )

    fun matches(
        request: ChatSubmission,
        input: SessionInputRecord,
    ): Boolean =
        request.sessionId == input.sessionId && request.clientRequestId == input.inputId &&
            input.configuration.fingerprint.substringAfterLast(':') == intentHash(request)

    fun matchesConfiguration(
        input: SessionInputRecord,
        providerFacts: String,
        control: RunControlConfig,
        selectedMode: String,
    ): Boolean =
        input.configuration.fingerprint.substringBeforeLast(':') ==
            "v1:${configurationHash(providerFacts, control, selectedMode)}"

    private fun configurationHash(
        providerFacts: String,
        control: RunControlConfig,
        selectedMode: String,
    ): String {
        // A default for future messages cannot invalidate already accepted queue entries.
        val executionControl = control.copy(immediateMessages = false)
        return hash("$providerFacts\n$executionControl\n$selectedMode")
    }

    private fun intentHash(request: ChatSubmission): String =
        hash(
            buildJsonObject {
                put("session", request.sessionId)
                put("input", request.clientRequestId)
                put("text", request.text)
                put("attachments", buildJsonArray { request.attachmentIds.forEach { add(it) } })
                put("delivery", request.delivery.name)
                put("target", request.expectedTurnId)
                put("revisionTarget", request.revisedMessageId)
                put("referenceSession", request.referenceSourceSessionId)
                put("referenceKind", request.referenceKind?.name)
            }.toString(),
        )

    private fun hash(value: String): String = FileContentStore.sha256Hex(value.toByteArray(Charsets.UTF_8))
}
