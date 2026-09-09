package com.helix.app.goal

import com.helix.core.model.ArtifactRef
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.workspace.AtomicFileWriter
import java.io.File

/** A private snapshot of an actual local ToolResult, explicitly distinct from the tool's output files. */
internal class GoalToolArtifactStore(
    private val storage: HelixStorage,
    private val workspace: File,
    private val reader: GoalToolEvidenceReader,
    private val editedSource: ScopedEditedArtifactReader? = null,
    private val readCheckpoint: () -> Unit = {},
) {
    private val archives = GoalArchiveArtifactStore(storage, workspace, reader)

    fun capture(
        goalId: String,
        toolCallId: String,
        written: Boolean = false,
    ): ArtifactEntity =
        goalEvidenceCheck(storage) {
            val snapshot = reader.read(goalId, toolCallId)
            val relative = relativePath(snapshot, written)
            val target = file(relative)
            val existing = storage.artifacts.findBySessionAndPath(snapshot.source.sessionId.value, relative)
            if (existing == null) {
                val bytes = content(snapshot, written)
                val parent = requireNotNull(target.parentFile)
                require(parent.mkdirs() || parent.isDirectory)
                val expectedHash = FileContentStore.sha256Hex(bytes)
                if (target.exists()) {
                    require(target.isFile && target.length() == bytes.size.toLong())
                    require(FileContentStore.sha256Hex(target) == expectedHash) { "conflicting artifact file" }
                } else {
                    AtomicFileWriter.writeAtomic(target.toPath(), bytes)
                }
                val hash = expectedHash
                verify(
                    snapshot,
                    ArtifactEntity(
                        artifactId(snapshot, written),
                        snapshot.source.sessionId.value,
                        relative,
                        "text/plain",
                        bytes.size.toLong(),
                        hash,
                    ),
                    written,
                )
                checkGoalEvidenceReadActive()
                storage.artifacts.register(
                    artifactId(snapshot, written),
                    snapshot.source.sessionId.value,
                    relative,
                    "text/plain",
                    bytes.size.toLong(),
                    hash,
                    target,
                )
            } else {
                verify(snapshot, existing, written)
                existing
            }
        }

    /** Revalidation never creates a missing file or row, including after explicit deletion. */
    fun read(
        goalId: String,
        toolCallId: String,
        reference: ArtifactRef,
    ): Pair<ArtifactEntity, ByteArray> =
        goalEvidenceCheck(storage) {
            if (reference.value.startsWith(GoalArchiveArtifactStore.PREFIX)) {
                return@goalEvidenceCheck archives.read(goalId, toolCallId, reference)
            }
            val snapshot = reader.read(goalId, toolCallId)
            val artifact = storage.artifacts.resolve(reference.value)
            artifact to verify(snapshot, artifact, reference.value.startsWith("goal-written-"))
        }

    private fun verify(
        snapshot: GoalToolEvidenceSnapshot,
        artifact: ArtifactEntity,
        written: Boolean,
    ): ByteArray {
        val edited = if (written && snapshot.call.name == "edit") editExpectation(snapshot) else null
        val expected = if (edited == null) content(snapshot, written) else null
        val size = edited?.size ?: requireNotNull(expected).size.toLong()
        val hash = edited?.sha256 ?: FileContentStore.sha256Hex(requireNotNull(expected))
        require(
            artifact.id == artifactId(snapshot, written) && artifact.relativePath == relativePath(snapshot, written),
        )
        require(artifact.sessionId == snapshot.source.sessionId.value && artifact.mediaType == "text/plain")
        require(artifact.size == size && artifact.sha256 == hash)
        val target = file(artifact.relativePath)
        requireGoalEvidence(target.isFile && target.length() == artifact.size, GoalEvidenceFailure.CONTENT_CHANGED)
        val actual =
            target.inputStream().use { input ->
                val bytes = ByteArray(size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    checkGoalEvidenceReadActive()
                    val count = input.read(bytes, offset, bytes.size - offset)
                    require(count > 0) { "artifact is truncated" }
                    offset += count
                    readCheckpoint()
                }
                require(input.read() == -1) { "artifact exceeds snapshot size" }
                bytes
            }
        if (edited != null) return edited.verify(actual)
        requireGoalEvidence(actual.contentEquals(requireNotNull(expected)), GoalEvidenceFailure.CONTENT_CHANGED)
        return actual
    }

    private fun file(relative: String): File {
        val root = workspace.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.toPath().startsWith(root.toPath()) && target != root) { "artifact escapes workspace" }
        return target
    }

    private fun relativePath(
        snapshot: GoalToolEvidenceSnapshot,
        written: Boolean,
    ): String = ".helix/goal-evidence/${if (written) "written-" else ""}${snapshot.hash.hex}.txt"

    private fun artifactId(
        snapshot: GoalToolEvidenceSnapshot,
        written: Boolean,
    ): String = "goal-${if (written) "written" else "evidence"}-${snapshot.hash.hex}"

    private fun editExpectation(snapshot: GoalToolEvidenceSnapshot) =
        EditedArtifactContent.parse(
            snapshot.call.name,
            snapshot.call.version,
            snapshot.call.argsJson,
            requireNotNull(snapshot.content),
        )

    private fun content(
        snapshot: GoalToolEvidenceSnapshot,
        written: Boolean,
    ): ByteArray {
        val body = requireNotNull(snapshot.content) { "tool result has no content snapshot" }
        return if (written && snapshot.call.name == "edit") {
            requireNotNull(editedSource) { "edited artifact source is unavailable" }.read(editExpectation(snapshot))
        } else if (written) {
            WrittenArtifactContent.decode(snapshot.call.name, snapshot.call.version, snapshot.call.argsJson, body)
        } else {
            body.toByteArray(Charsets.UTF_8)
        }
    }
}
