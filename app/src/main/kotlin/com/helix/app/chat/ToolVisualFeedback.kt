package com.helix.app.chat

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.provider.ProviderService
import com.helix.app.vision.ToolImageWindow
import com.helix.app.vision.ToolVisionConsent
import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderTransport
import com.helix.core.model.ToolImageOmission
import com.helix.core.model.VisualArtifact
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Rebuilds TOOL pixels from committed attachment relations, never from arbitrary tool JSON alone. */
internal class ToolVisualFeedback(
    private val attachmentsFor: (String) -> List<com.helix.core.storage.entity.MessageAttachmentEntity>,
    private val artifactFor: (String) -> com.helix.core.storage.entity.ArtifactEntity,
    private val visionAvailable: suspend (String, String) -> Boolean,
    private val configFor: suspend (String) -> ProviderConfig,
    private val consent: ToolVisionConsent,
) {
    constructor(storage: HelixStorage, providers: ProviderService, consent: ToolVisionConsent) : this(
        storage.messageAttachments::listByMessage,
        storage.artifacts::resolve,
        { provider, model -> providers.capabilitiesFor(provider, model)?.vision == true },
        providers::storedConfig,
        consent,
    )

    suspend fun restore(
        sessionId: String,
        turnId: String?,
        config: ProviderConfig,
        model: String,
        messages: List<ModelMessage>,
        rows: List<ChatHistoryBuilder.PersistedRow>,
        imagesFor: suspend (String) -> List<ImageReference>,
    ): List<ModelMessage> {
        val toolRows = rows.filter { it.role == ModelRole.TOOL.name && it.kind == ChatHistoryBuilder.KIND_TOOL_RESULT }
        val toolIndices = messages.indices.filter { messages[it].role == ModelRole.TOOL }
        require(toolRows.size == toolIndices.size) { "Tool observation rows do not match model results" }
        val bound =
            toolRows.map { row ->
                row.messageId
                    ?.let(attachmentsFor)
                    .orEmpty()
                    .filter { it.purpose == AttachmentPurpose.TOOL_OBSERVATION }
            }
        var remaining =
            ToolImageWindow.remaining(
                messages.flatMap { it.images }.map { artifactFor(it.ref.value).size },
            )
        if (bound.all { it.isEmpty() }) return messages
        val selected =
            ToolImageWindow.select(
                turnId,
                toolRows.indices.map { if (bound[it].isNotEmpty()) toolRows[it].turnId else null },
            )
        val output = messages.toMutableList()
        val target = Target(sessionId, turnId, config, model)
        val vision = visionAvailable(config.id, model)
        for (index in toolRows.indices.filter { bound[it].isNotEmpty() }) {
            val messageIndex = toolIndices[index]
            val message = messages[messageIndex]
            val projected =
                when {
                    index !in selected -> Projection(note(message, ToolImageOmission.OUTSIDE_RECENT_WINDOW))
                    !vision -> Projection(note(message, ToolImageOmission.VISION_UNAVAILABLE))
                    else -> observe(target, toolRows[index], message, remaining, imagesFor)
                }
            output[messageIndex] = projected.message
            remaining -= projected.bytes
        }
        return output
    }

    private data class Target(
        val sessionId: String,
        val turnId: String?,
        val config: ProviderConfig,
        val model: String,
    )

    private data class Projection(
        val message: ModelMessage,
        val bytes: Long = 0,
    )

    private suspend fun observe(
        target: Target,
        row: ChatHistoryBuilder.PersistedRow,
        message: ModelMessage,
        remaining: Long,
        imagesFor: suspend (String) -> List<ImageReference>,
    ): Projection {
        val image = imagesFor(requireNotNull(row.messageId)).single()
        val artifact = artifactFor(image.ref.value)
        val bytes = ToolImageWindow.base64Bytes(artifact.size)
        if (bytes > remaining) return Projection(note(message, ToolImageOmission.IMAGE_BUDGET_EXCEEDED))
        val facts = visualFacts(requireNotNull(row.content))
        require(
            facts.artifactId == artifact.id && facts.sha256 == artifact.sha256 &&
                facts.sizeBytes == artifact.size && facts.mediaType == artifact.mediaType &&
                artifact.sessionId == target.sessionId && artifact.turnId == target.turnId,
        ) {
            "Tool image provenance changed"
        }
        val requested =
            image.copy(
                binding =
                    requireNotNull(image.binding).copy(
                        turnId = target.turnId,
                        modelId = target.model,
                    ),
            )
        val receipt = disclose(target, message, requested, facts)
        return if (receipt == null) {
            Projection(note(message, ToolImageOmission.DISCLOSURE_UNAVAILABLE))
        } else {
            verifyTarget(target, requested)
            Projection(
                message.copy(
                    images =
                        listOf(
                            requested.copy(
                                binding = requireNotNull(requested.binding).copy(consentId = receipt),
                            ),
                        ),
                ),
                bytes,
            )
        }
    }

    private suspend fun disclose(
        target: Target,
        message: ModelMessage,
        image: ImageReference,
        facts: VisualArtifact,
    ): String? {
        val config = target.config
        val destination =
            EgressDisclosure.EgressTarget(
                config.id,
                "${config.displayName} / ${target.model}",
                (config.transport as? ProviderTransport.Network)?.protocol,
                (config.transport as? ProviderTransport.Network)?.endpoint?.origin.orEmpty(),
                config.residence(),
            )
        val source = "${message.toolName!!.value}: ${facts.artifactId} (${facts.sha256.take(12)})"
        val decision =
            EgressDisclosure.decide(
                listOf(
                    EgressDisclosure.OutgoingContent.Image(
                        source,
                        facts.sizeBytes,
                        facts.sha256,
                        facts.mediaType,
                        facts.width,
                        facts.height,
                    ),
                ),
                "",
                destination,
            )
        return if (decision is EgressDisclosure.Decision.Confirm) {
            consent.request(
                image,
                config,
                decision.summary,
            )
        } else {
            null
        }
    }

    private suspend fun verifyTarget(
        target: Target,
        image: ImageReference,
    ) {
        val latest = configFor(target.config.id)
        check(ToolVisionConsent.identity(image, latest) == ToolVisionConsent.identity(image, target.config)) {
            "Image destination changed; request fresh consent"
        }
        check(visionAvailable(target.config.id, target.model)) { "Vision capability changed" }
    }

    private fun visualFacts(content: String): VisualArtifact {
        val value =
            Json
                .parseToJsonElement(content)
                .jsonObject
                .getValue("visualArtifact")
                .jsonObject
        return VisualArtifact(
            value.getValue("artifactId").jsonPrimitive.content,
            value.getValue("sha256").jsonPrimitive.content,
            value.getValue("mediaType").jsonPrimitive.content,
            value.getValue("sizeBytes").jsonPrimitive.long,
            value.getValue("width").jsonPrimitive.int,
            value.getValue("height").jsonPrimitive.int,
        )
    }

    private fun note(
        message: ModelMessage,
        reason: ToolImageOmission,
    ) = message.copy(images = emptyList(), imageOmission = reason)
}
