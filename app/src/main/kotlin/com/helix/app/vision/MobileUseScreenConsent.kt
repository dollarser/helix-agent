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
    sources: LineStore = InMemoryLineStore(),
    private val scopeForConversation: (String) -> AutomationSessionScope? = { null },
) {
    private val sources = MobileScreenSources(sources)

    /** Only the native screenshot publisher calls this; tool JSON cannot manufacture screen provenance. */
    @Synchronized
    fun register(
        call: ExecutableToolCall,
        image: VisualArtifact,
        scopeRef: String,
        capturedSha256: String? = null,
    ) {
        val session = requireNotNull(call.sessionId)
        require(call.toolName == "ui.screenshot" && scopeRef == currentScope(session)) {
            "Screen acquisition grant is no longer available"
        }
        val captured = MobileScreenSource(scopeRef, session, requireNotNull(call.turnId), image.sha256, image.mediaType)
        sources.rememberArtifact(image.artifactId, captured)
        sources.rememberContent(captured)
        capturedSha256?.let { sources.rememberContent(captured.copy(sha256 = it, mediaType = "image/png")) }
    }

    /** Only host-computed bytes from the scoped read may match a trusted capture in this Conversation. */
    @Synchronized
    fun inherit(
        call: ExecutableToolCall,
        sourceSha256: String,
        sourceMediaType: String,
        image: VisualArtifact,
    ) {
        if (call.toolName != "view_image") return
        val session = requireNotNull(call.sessionId)
        val captured =
            sources.forContent(session, sourceSha256, sourceMediaType)?.takeIf {
                it.sessionId == session && it.sha256 == sourceSha256 &&
                    it.mediaType == sourceMediaType && it.scopeRef == currentScope(session)
            } ?: return
        val derived =
            captured.copy(
                turnId = requireNotNull(call.turnId),
                sha256 = image.sha256,
                mediaType = image.mediaType,
            )
        sources.rememberArtifact(image.artifactId, derived)
        sources.rememberContent(derived)
    }

    /** No dialog: enabling Mobile Use covers future screenshots and the user's selected conversation models. */
    @Synchronized
    fun requestId(
        image: ImageReference,
        config: ProviderConfig,
    ): String? {
        val binding = image.binding
        val source = sources.forArtifact(image.ref.value)
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
    fun isScreenImage(image: ImageReference): Boolean = sources.forArtifact(image.ref.value) != null

    private fun currentScope(session: String): String? =
        scopeForConversation(session)
            ?.takeIf { it.grantId.isNotBlank() && clock.now().isBefore(it.expiresAt) }
            ?.toScopeRef()

    private fun digest(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFIX = "mobile-screen-consent-"
    }
}
