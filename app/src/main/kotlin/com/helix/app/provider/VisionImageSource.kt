package com.helix.app.provider

import com.helix.core.model.ArtifactRef
import com.helix.core.model.VisionLimits
import com.helix.core.storage.repository.ArtifactRepository
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.provider.api.CapabilityProbe
import java.util.Base64

/**
 * One resolved image for the wire (HXA-055): the registered [mediaType] (closed set) plus the
 * base64 of the verified bytes — exactly the material the three protocol resolvers encode
 * (data URL / `input_image` / `image` block).
 */
data class LoadedImage(
    val mediaType: String,
    val base64: String,
)

/**
 * The single production image source for ALL three protocol adapters (HXA-055; the adapters
 * keep their independent resolvers by design, this is the shared app-side SOURCE, not shared
 * protocol code).
 *
 * Fail-closed contract (ADR-0014 §4): [load] resolves ONLY session-message-bound, hash-verified
 * app-private artifacts. Any miss — unknown ref, wrong session scope, over-budget size, a media
 * type outside the closed set, or bytes whose magic signature disagrees with the registered
 * type — throws [IllegalArgumentException] with a SHORT, stable, path-free message; the protocol
 * encoder propagates it and the turn fails with an actionable error. There is no raw-base64
 * fallback and no placeholder substitution (the encoder contract of HXA-022/023).
 */
fun interface VisionImageSource {
    fun load(ref: ArtifactRef): LoadedImage
}

/**
 * The production [VisionImageSource]: the Room `artifacts` registry (the message-binding proof)
 * + the containment-enforced workspace store (the bytes). The workspace scope is the app scope —
 * every chat attachment artifact (raw or normalized) is staged there by [com.helix.app.chat.ChatService].
 */
class ArtifactVisionImageSource(
    private val artifacts: ArtifactRepository,
    private val workspace: WorkspaceArtifactStore,
    private val scopeId: String,
) : VisionImageSource {
    @Suppress("TooGenericExceptionCaught") // ANY read failure (path/scope/I-O) maps to one closed, path-free error
    override fun load(ref: ArtifactRef): LoadedImage {
        if (ref == PROBE_IMAGE_REF) return PROBE_IMAGE
        val artifact =
            artifacts
                .listBySession(currentSession())
                .firstOrNull { it.id == ref.value }
                ?: throw IllegalArgumentException("image artifact is not bound to this session")
        require(artifact.mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES) {
            "image artifact has a non-closed media type"
        }
        require(artifact.size <= VisionLimits.MAX_NORMALIZED_RAW_BYTES) {
            "image exceeds the per-image wire budget"
        }
        val bytes =
            try {
                workspace.readAll(FileScopePath(scopeId, artifact.relativePath))
            } catch (e: Exception) {
                throw IllegalArgumentException("image artifact is unreadable", e)
            }
        require(bytes.isNotEmpty() && bytes.size.toLong() == artifact.size) {
            "image artifact bytes do not verify against their snapshot"
        }
        // MIME/signature consistency (HXA-055): the BYTES must agree with the registered type —
        // a relabeled file never reaches the wire.
        val magic = ContentProbe.probeBytes(bytes, bytes.size.toLong()).mimeType
        require(magic == artifact.mediaType) { "image bytes do not match their registered media type" }
        return LoadedImage(artifact.mediaType, Base64.getEncoder().encodeToString(bytes))
    }

    /**
     * The artifact registry is session-indexed but the ref carries no session, so the chat
     * service binds the session under [bindSession] before each turn build. A source with no
     * bound session fails closed — it never guesses a session.
     */
    @Volatile
    private var boundSession: String? = null

    /** Binds the session for the in-flight turn build (the resolver runs inside the stream). */
    fun bindSession(sessionId: String) {
        boundSession = sessionId
    }

    private fun currentSession(): String =
        boundSession ?: throw IllegalArgumentException("image artifact resolution has no session context")

    companion object {
        /**
         * The reserved ref of the built-in 1x1 probe image the vision capability probe (the
         * connection test's phase 5) sends — it resolves without any session artifact.
         * The single source of truth is [CapabilityProbe.VISION_PROBE_REF].
         */
        val PROBE_IMAGE_REF: ArtifactRef = CapabilityProbe.VISION_PROBE_REF

        /**
         * A 1x1 transparent PNG (67 bytes) — the smallest valid image every provider accepts;
         * the probe's only payload, so no user data ever rides the capability probe.
         */
        val PROBE_IMAGE: LoadedImage =
            LoadedImage(
                mediaType = "image/png",
                base64 =
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
            )
    }
}
