package com.helix.app.goal

import com.helix.app.proot.ProotGoalArtifacts
import com.helix.core.model.ArtifactRef
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.workspace.AtomicFileWriter
import java.io.File

/** Each reference binds one archive entry to the complete verified local ToolResult fingerprint. */
internal class GoalArchiveArtifactStore(
    private val storage: HelixStorage,
    private val workspace: File,
    private val reader: GoalToolEvidenceReader,
) {
    private val source = ProotGoalArtifacts(storage, workspace)

    fun paths(
        goalId: String,
        callId: String,
    ): List<String> {
        val snapshot = reader.read(goalId, callId)
        if (snapshot.call.name !in setOf("bash", "code.linux.run") || snapshot.call.version != "1") {
            return emptyList()
        }
        return source.paths(snapshot.call.turnId, snapshot.call.callId)
    }

    fun capture(
        goalId: String,
        callId: String,
        path: String,
    ): ArtifactEntity =
        goalEvidenceCheck(storage) {
            val snapshot = reader.read(goalId, callId)
            val reference = reference(snapshot, path)
            val relative = relative(reference)
            val existing = storage.artifacts.findBySessionAndPath(snapshot.source.sessionId.value, relative)
            if (existing != null) {
                read(goalId, callId, ArtifactRef(reference)).first
            } else {
                val bytes = source.read(snapshot.call.turnId, snapshot.call.callId, path)
                val target = file(relative)
                val parent = requireNotNull(target.parentFile)
                require(parent.mkdirs() || parent.isDirectory)
                val hash = FileContentStore.sha256Hex(bytes)
                if (target.exists()) {
                    require(target.isFile && target.length() == bytes.size.toLong())
                    require(FileContentStore.sha256Hex(target) == hash)
                } else {
                    AtomicFileWriter.writeAtomic(target.toPath(), bytes)
                }
                checkGoalEvidenceReadActive()
                storage.artifacts.register(
                    reference,
                    snapshot.source.sessionId.value,
                    relative,
                    "application/octet-stream",
                    bytes.size.toLong(),
                    hash,
                    target,
                )
            }
        }

    fun read(
        goalId: String,
        callId: String,
        reference: ArtifactRef,
    ): Pair<ArtifactEntity, ByteArray> =
        goalEvidenceCheck(storage) {
            val snapshot = reader.read(goalId, callId)
            val path =
                requireNotNull(
                    source.paths(snapshot.call.turnId, snapshot.call.callId).singleOrNull {
                        reference(snapshot, it) == reference.value
                    },
                ) { "archive reference does not belong to this source" }
            val bytes = source.read(snapshot.call.turnId, snapshot.call.callId, path)
            val artifact = storage.artifacts.resolve(reference.value)
            require(
                artifact.sessionId == snapshot.source.sessionId.value &&
                    artifact.relativePath == relative(reference.value),
            )
            require(artifact.mediaType == "application/octet-stream" && artifact.size == bytes.size.toLong())
            require(artifact.sha256 == FileContentStore.sha256Hex(bytes))
            val target = file(artifact.relativePath)
            requireGoalEvidence(
                target.isFile && target.length() == artifact.size &&
                    FileContentStore.sha256Hex(target) == artifact.sha256,
                GoalEvidenceFailure.CONTENT_CHANGED,
            )
            artifact to bytes
        }

    private fun reference(
        snapshot: GoalToolEvidenceSnapshot,
        path: String,
    ): String = "$PREFIX${FileContentStore.sha256Hex("${snapshot.hash.hex}:$path".toByteArray(Charsets.UTF_8))}"

    private fun relative(reference: String): String =
        ".helix/goal-evidence/archive-${FileContentStore.sha256Hex(reference.toByteArray(Charsets.UTF_8))}.bin"

    private fun file(relative: String): File {
        val root = workspace.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.toPath().startsWith(root.toPath()) && target != root)
        return target
    }

    companion object {
        const val PREFIX = "goal-archive-"
    }
}
