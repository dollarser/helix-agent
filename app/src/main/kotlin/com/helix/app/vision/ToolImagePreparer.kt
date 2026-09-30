package com.helix.app.vision

import com.helix.core.model.Clock
import com.helix.core.model.VisionLimits
import com.helix.core.model.VisualArtifact
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.feature.files.ImageNormalizer
import com.helix.feature.files.NormalizationOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolVisualPreparation
import com.helix.tools.framework.VisualPreparationException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Local derivative snapshots share the existing Artifact store and message-attachment lifecycle. */
class ToolImagePreparer(
    private val storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    private val sink: WorkspaceArtifactStore.ArtifactSink,
    private val artifactScope: String,
    private val temporaryRoot: File,
    private val clock: Clock,
    private val visionAvailable: (String) -> Boolean,
    private val normalize: (Path, String, Path) -> NormalizationOutcome = ImageNormalizer::normalize,
    private val turnVisionAvailable: ((String, String) -> Boolean)? = null,
) : ToolVisualPreparation {
    private val decodeSlot = Semaphore(1, true)

    override fun prepare(
        call: ExecutableToolCall,
        reference: String,
        expectedSha256: String?,
    ): VisualArtifact {
        val session = call.sessionId ?: throw VisualPreparationException("SESSION_REQUIRED")
        val turn = call.turnId ?: throw VisualPreparationException("TURN_REQUIRED")
        if (storage.turns.resolve(turn).sessionId != session) throw VisualPreparationException("SESSION_MISMATCH")
        if (!(turnVisionAvailable?.invoke(session, turn) ?: visionAvailable(session))) {
            throw VisualPreparationException("VISION_UNAVAILABLE")
        }
        val path = FileScopePath.fromModelReference(reference)
        var acquired = false
        var directory: File? = null
        try {
            while (!acquired) {
                checkActive(call)
                acquired = decodeSlot.tryAcquire(100, TimeUnit.MILLISECONDS)
            }
            check(temporaryRoot.isDirectory || temporaryRoot.mkdirs()) { "Image staging unavailable" }
            directory = Files.createTempDirectory(temporaryRoot.toPath(), "image-").toFile()
            return normalizeAndPublish(call, path, expectedSha256, directory)
        } catch (e: VisualPreparationException) {
            throw e
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VisualPreparationException("CANCELLED")
        } catch (_: IOException) {
            throw VisualPreparationException("UNREADABLE_OR_STORAGE_UNAVAILABLE")
        } catch (_: SecurityException) {
            throw VisualPreparationException("SCOPE_UNAVAILABLE")
        } catch (_: IllegalStateException) {
            throw VisualPreparationException("IMAGE_PUBLICATION_FAILED")
        } finally {
            try {
                directory?.deleteRecursively()
            } finally {
                if (acquired) decodeSlot.release()
            }
        }
    }

    private fun normalizeAndPublish(
        call: ExecutableToolCall,
        path: FileScopePath,
        expectedSha256: String?,
        directory: File,
    ): VisualArtifact {
        val raw = File(directory, "source")
        copyBounded(call, path, raw)
        val hash = AtomicFileWriter.sha256Hex(raw.toPath())
        ensure(expectedSha256 == null || expectedSha256 == hash, "SOURCE_CHANGED")
        val type = ContentProbe.probe(raw.toPath()).mimeType
        ensure(type in VisionLimits.NORMALIZED_MEDIA_TYPES, "UNSUPPORTED_IMAGE")
        checkActive(call)
        val normalized =
            when (val result = normalize(raw.toPath(), type, directory.toPath())) {
                is NormalizationOutcome.Ok -> result.image
                is NormalizationOutcome.Failed -> throw VisualPreparationException(result.code.name)
            }
        checkActive(call)
        ensure(Files.size(normalized.file) in 1..VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong(), "NORMALIZED_TOO_LARGE")
        ensure(VisionLimits.normalizedEdgeFits(normalized.width, normalized.height), "DIMENSIONS_EXCEEDED")
        val session = requireNotNull(call.sessionId)
        val destination = FileScopePath(artifactScope, "output/vision-${UUID.randomUUID()}.image")
        val written =
            workspace.writeArtifact(
                destination,
                Files.readAllBytes(normalized.file),
                WorkspaceLayout.OUTPUT,
                sessionId = session,
                sink = sink,
                turnId = call.turnId,
            )
        val artifact = requireNotNull(storage.artifacts.findBySessionAndPath(session, destination.toModelReference()))
        check(written.record.sha256 == normalized.sha256 && artifact.sha256 == normalized.sha256)
        return VisualArtifact(
            artifact.id,
            artifact.sha256,
            artifact.mediaType,
            artifact.size,
            normalized.width,
            normalized.height,
        )
    }

    private fun copyBounded(
        call: ExecutableToolCall,
        path: FileScopePath,
        target: File,
    ) {
        workspace.openRead(path).use { input ->
            target.outputStream().use { output -> transfer(call, input, output) }
        }
    }

    private fun transfer(
        call: ExecutableToolCall,
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            checkActive(call)
            val count = input.read(buffer)
            if (count < 0) break
            ensure(count > 0, "NO_READ_PROGRESS")
            total += count
            ensure(total <= VisionLimits.MAX_INPUT_BYTES, "INPUT_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        ensure(total > 0, "EMPTY_IMAGE")
    }

    private fun ensure(
        condition: Boolean,
        code: String,
    ) {
        if (!condition) throw VisualPreparationException(code)
    }

    private fun checkActive(call: ExecutableToolCall) {
        if (call.cancel.isCancelled() ||
            Thread.currentThread().isInterrupted
        ) {
            throw VisualPreparationException("CANCELLED")
        }
        if (!clock.now().isBefore(call.deadline)) throw VisualPreparationException("PREPARATION_DEADLINE")
    }
}
