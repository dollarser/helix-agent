package com.helix.app.vision

import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.ImageReference
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ProviderConfig

/** Last byte-materialization check: exact message, session, hash and disclosed destination. */
class BoundImageAccess(
    private val storage: HelixStorage,
    private val consent: ToolVisionConsent,
    private val visionAvailable: (String, String) -> Boolean,
) {
    fun verify(
        image: ImageReference,
        config: ProviderConfig,
    ) {
        val binding = requireNotNull(image.binding) { "Image has no request-scoped binding" }
        val row = storage.messages.resolve(binding.messageId)
        require(row.sessionId == binding.sessionId) { "Image message belongs to another session" }
        val attachment =
            storage.messageAttachments.listByMessage(binding.messageId).singleOrNull {
                it.artifactId == image.ref.value && it.boundSha256 == binding.sha256
            } ?: throw IllegalArgumentException("Image attachment binding changed")
        if (attachment.purpose == AttachmentPurpose.TOOL_OBSERVATION) {
            require(row.role == "TOOL" && row.turnId == binding.turnId) { "Tool image provenance changed" }
            val turn = storage.turns.resolve(requireNotNull(binding.turnId))
            require(
                !com.helix.core.model.TurnState
                    .valueOf(turn.state)
                    .isTerminal && turn.state != "CANCELLING",
            ) {
                "Tool image Turn is no longer active"
            }
            require(
                visionAvailable(config.id, requireNotNull(binding.modelId)),
            ) { "Vision capability is no longer available" }
            require(
                consent.granted(image, config, requireNotNull(binding.consentId)),
            ) { "Tool image disclosure not granted" }
            val current = storage.providerConfigs.resolve(config.id)
            val latest =
                ProviderConfig.fromStorage(
                    current.id,
                    current.displayName,
                    current.protocol,
                    current.endpoint,
                    current.model,
                    current.headersJson,
                    current.secretAlias,
                    current.capabilitySnapshot,
                    current.provisioningKind,
                    current.transportKind,
                    current.authKind,
                )
            require(ToolVisionConsent.identity(image, latest) == ToolVisionConsent.identity(image, config)) {
                "Tool image destination changed"
            }
        } else {
            require(attachment.purpose == AttachmentPurpose.REFERENCE && row.role == "USER") {
                "Image source role is invalid"
            }
        }
    }
}
