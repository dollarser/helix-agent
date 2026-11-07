package com.helix.app.provider

import com.helix.app.vision.ImageReadPolicy
import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageReselectionRequiredException
import com.helix.core.storage.repository.ArtifactRepository
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.NormalizedImageExif
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
    fun load(
        image: com.helix.core.model.ImageReference,
        config: com.helix.provider.api.ProviderConfig,
    ): LoadedImage
}

/**
 * The production [VisionImageSource]: the Room `artifacts` registry (the message-binding proof)
 * + the containment-enforced workspace store (the bytes). The file's scope is carried by the
 * stored full `scope:` reference (parsed via [FileScopePath.fromModelReference]), so the bytes
 * are read back from exactly the scope the artifact was registered under — never an assumed one.
 */
class ArtifactVisionImageSource(
    private val artifacts: ArtifactRepository,
    private val workspace: WorkspaceArtifactStore,
    private val verifyBinding: (
        com.helix.core.model.ImageReference,
        com.helix.provider.api.ProviderConfig,
    ) -> ImageReadPolicy =
        { _, _ -> throw IllegalArgumentException("No image binding verifier installed") },
) : VisionImageSource {
    @Suppress("TooGenericExceptionCaught") // Decode/scope failures must not leak private paths into model errors.
    override fun load(
        image: com.helix.core.model.ImageReference,
        config: com.helix.provider.api.ProviderConfig,
    ): LoadedImage {
        val probe =
            when (image.ref) {
                PROBE_IMAGE_REF -> PROBE_IMAGE
                COLOR_PROBE_REF -> COLOR_PROBE_IMAGE
                else -> null
            }
        if (probe != null) return probe
        return try {
            loadBound(image, config)
        } catch (failure: java.util.concurrent.CancellationException) {
            throw failure
        } catch (failure: ImageReselectionRequiredException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalArgumentException("Image source or disclosure could not be verified", failure)
        }
    }

    private fun loadBound(
        image: com.helix.core.model.ImageReference,
        config: com.helix.provider.api.ProviderConfig,
    ): LoadedImage {
        val binding = requireNotNull(image.binding) { "Image has no request binding" }
        checkInterrupted()
        val policy = verifyBinding(image, config)
        val artifact = artifacts.resolve(image.ref.value)
        require(
            artifact.sessionId == binding.sessionId && artifact.sha256 == binding.sha256 &&
                artifact.mediaType == image.mediaType,
        ) { "Image request binding changed" }
        val exact =
            com.helix.app.vision.VerifiedImageBytes.read(artifact.size, binding.sha256, image.mediaType) {
                workspace.openRead(FileScopePath.fromModelReference(artifact.relativePath))
            }
        checkInterrupted()
        require(verifyBinding(image, config) == policy) { "Image source purpose changed" }
        if (policy == ImageReadPolicy.NORMALIZED_USER_REFERENCE &&
            !NormalizedImageExif.isExifFree(exact, image.mediaType)
        ) {
            throw ImageReselectionRequiredException()
        }
        return LoadedImage(image.mediaType, Base64.getEncoder().encodeToString(exact))
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
    }

    companion object {
        /**
         * The reserved ref of the built-in 1x1 probe image the vision capability probe (the
         * connection test's phase 5) sends — it resolves without any session artifact.
         * The single source of truth is [CapabilityProbe.VISION_PROBE_REF].
         */
        val PROBE_IMAGE_REF: ArtifactRef = CapabilityProbe.VISION_PROBE_REF
        val COLOR_PROBE_REF = ArtifactRef("helix:vision-color-probe")
        private val COLOR_PROBE_IMAGE =
            LoadedImage(
                "image/png",
                "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAb0lEQVR4nO3PAQkAAAyEwO9feoshgnAB" +
                    "dLep8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjx" +
                    "BQ3I8QUNyPEFDcjxBQ3I8QUNyPEFDcjxBQ3IPanc8OLDQitxAAAAAElFTkSuQmCC",
            )

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
