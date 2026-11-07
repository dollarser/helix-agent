package com.helix.feature.conversation.application.input

import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SessionInputConfiguration
import com.helix.core.model.input.InputConfiguration
import com.helix.feature.conversation.input.ChatSubmission
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Binds producer identity independently of normalized image artifact identities. Contains no secrets. */
object SessionInputBinding {
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
            "v1:${SessionInputConfiguration.hash(providerFacts, control, selectedMode)}:${intentHash(request)}",
        )

    fun matches(
        request: ChatSubmission,
        input: AcceptedInputReceipt,
    ): Boolean =
        request.sessionId == input.sessionId && request.clientRequestId == input.inputId &&
            input.configurationFingerprint.substringAfterLast(':') == intentHash(request)

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
                put(
                    "knowledgeReferences",
                    com.helix.core.model.input.KnowledgeReferenceCodec
                        .encode(request.knowledgeReferences),
                )
                put(
                    "skills",
                    com.helix.core.model.input.InputSkillActivations
                        .encode(request.skillActivations),
                )
            }.toString(),
        )

    private fun hash(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
