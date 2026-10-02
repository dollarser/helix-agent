package com.helix.app.vision

import com.helix.app.internal.InMemoryLineStore
import com.helix.app.internal.LineStore
import com.helix.core.model.Clock
import com.helix.core.model.ImageReference
import com.helix.core.model.ProviderTransport
import com.helix.core.model.VisualArtifact
import com.helix.core.policy.AutomationSessionScope
import com.helix.provider.api.ProviderConfig
import com.helix.tools.framework.ExecutableToolCall
import java.security.MessageDigest

/** Information only: a user-selected model change does not revoke Conversation screen sharing. */
data class MobileUseScreenTarget(
    val sessionId: String,
    val config: ProviderConfig,
    val model: String,
) {
    val local: Boolean get() = config.transport == ProviderTransport.OnDeviceLocal
    val label: String get() = "${config.displayName} / $model"
    val origin: String get() = (config.transport as? ProviderTransport.Network)?.endpoint?.origin.orEmpty()
}

/** Persistent screenshot provenance + the owning Conversation's explicit screen-sharing configuration. */
class MobileUseScreenConsent(
    private val clock: Clock,
    private val sources: LineStore = InMemoryLineStore(),
    private val scopeForConversation: (String) -> AutomationSessionScope? = { null },
) {
    private data class Source(
        val scopeRef: String,
        val sessionId: String,
        val turnId: String,
        val sha256: String,
        val mediaType: String,
    )

    /** Only the native screenshot publisher calls this; tool JSON cannot manufacture screen provenance. */
    @Synchronized
    fun register(
        call: ExecutableToolCall,
        image: VisualArtifact,
        scopeRef: String,
    ) {
        val session = requireNotNull(call.sessionId)
        require(call.toolName == "ui.screenshot" && scopeRef == currentScope(session)) {
            "Screen acquisition grant is no longer available"
        }
        val captured = Source(scopeRef, session, requireNotNull(call.turnId), image.sha256, image.mediaType)
        val previous = source(image.artifactId)
        check(previous == null || previous == captured) { "Screen acquisition provenance is immutable" }
        if (previous != null) return
        sources.setLines(
            key(image.artifactId),
            listOf(VERSION, scopeRef, session, requireNotNull(call.turnId), image.sha256, image.mediaType),
        )
    }

    /** No dialog: enabling Mobile Use covers future screenshots and the user's selected conversation models. */
    @Synchronized
    fun requestId(
        image: ImageReference,
        config: ProviderConfig,
    ): String? {
        val binding = image.binding
        val source = source(image.ref.value)
        if (binding == null || source == null) return null
        val matches =
            source.sessionId == binding.sessionId && source.turnId == binding.turnId &&
                source.sha256 == binding.sha256 && source.mediaType == image.mediaType
        // Keep each request bound to its actual content/recipient without asking the user again.
        return if (matches && source.scopeRef == currentScope(binding.sessionId)) {
            PREFIX + digest(source.scopeRef + ":" + ToolVisionConsent.identity(image, config))
        } else {
            null
        }
    }

    @Synchronized
    fun granted(
        image: ImageReference,
        config: ProviderConfig,
        id: String,
    ): Boolean = requestId(image, config) == id

    @Synchronized
    fun isScreenImage(image: ImageReference): Boolean = source(image.ref.value) != null

    private fun source(artifactId: String): Source? {
        val fields = sources.lines(key(artifactId))
        if (fields.isEmpty()) return null
        check(fields.size == 6 && fields[0] == VERSION) { "Screen provenance is unreadable" }
        return Source(fields[1], fields[2], fields[3], fields[4], fields[5])
    }

    private fun currentScope(session: String): String? =
        scopeForConversation(session)
            ?.takeIf { it.grantId.isNotBlank() && clock.now().isBefore(it.expiresAt) }
            ?.toScopeRef()

    private fun key(artifactId: String) = "mobile-screen-source-" + digest(artifactId)

    private fun digest(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFIX = "mobile-screen-consent-"
        private const val VERSION = "mobile-screen-source-v1"
    }
}
