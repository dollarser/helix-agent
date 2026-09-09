package com.helix.app.a2a

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.extensions.a2a.A2aRemoteArtifact
import com.helix.extensions.a2a.A2aRemotePart
import com.helix.extensions.a2a.A2aTaskUpdate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

internal class A2aTaskArtifacts(
    private val storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val resolveWorkspaceFile: (FileScopePath) -> java.io.File,
) {
    fun output(
        update: A2aTaskUpdate,
        sessionId: String,
        toolCallId: String,
    ): JsonObject =
        buildJsonObject {
            put("trust", UNTRUSTED_MARKER)
            put("state", update.state.name)
            update.taskId?.let { put("taskId", it) }
            update.contextId?.let { put("contextId", it) }
            put("parts", buildJsonArray { update.parts.forEach { add(it.toJson()) } })
            put(
                "artifacts",
                buildJsonArray {
                    update.artifacts.forEachIndexed { index, artifact ->
                        add(importArtifact(artifact, index, sessionId, toolCallId))
                    }
                },
            )
        }

    private fun A2aRemotePart.toJson(): JsonObject =
        buildJsonObject {
            put("trust", UNTRUSTED_MARKER)
            when (this@toJson) {
                is A2aRemotePart.Text -> put("text", text)
                is A2aRemotePart.Data -> put("data", data)
                is A2aRemotePart.Raw -> put("rawRejected", "raw content is imported only from Artifact parts")
                is A2aRemotePart.Url -> put("urlRejected", "remote URLs require a separately verified download")
            }
        }

    private fun importArtifact(
        artifact: A2aRemoteArtifact,
        index: Int,
        sessionId: String,
        toolCallId: String,
    ): JsonObject {
        val rawParts = artifact.parts.filterIsInstance<A2aRemotePart.Raw>()
        val urls = artifact.parts.filterIsInstance<A2aRemotePart.Url>()
        val imported =
            rawParts.mapIndexed { partIndex, part ->
                val bytes = decodeBounded(part.base64)
                val filename =
                    "$index-$partIndex-${safeFilename(part.filename ?: "artifact.bin")}"
                val path = FileScopePath(workspaceScopeId, "${WorkspaceLayout.OUTPUT}/a2a/$toolCallId/$filename")
                importRawArtifact(path, bytes, sessionId)
            }
        return buildJsonObject {
            put("remoteArtifactId", artifact.artifactId)
            artifact.name?.let { put("name", it) }
            put(
                "parts",
                buildJsonArray { artifact.parts.filterNot { it is A2aRemotePart.Raw }.forEach { add(it.toJson()) } },
            )
            put("imported", JsonArray(imported))
            if (urls.isNotEmpty()) put("remoteUrlsRejected", urls.size)
            put("trust", UNTRUSTED_MARKER)
        }
    }

    /**
     * A completed remote Task can be reconciled more than once, including after process death.
     * Reuse the exact previously registered artifact only after re-verifying its durable bytes;
     * never overwrite a changed file or create a second reference for the same remote part.
     */
    private fun importRawArtifact(
        path: FileScopePath,
        bytes: ByteArray,
        sessionId: String,
    ): JsonObject =
        synchronized(ARTIFACT_IMPORT_LOCK) {
            val expectedHash = FileContentStore.sha256Hex(bytes)
            storage.artifacts.findBySessionAndPath(sessionId, path.relativePath)?.let { existing ->
                val file = resolveWorkspaceFile(path)
                require(
                    existing.size == bytes.size.toLong() &&
                        existing.sha256 == expectedHash &&
                        file.isFile &&
                        file.length() == existing.size &&
                        FileContentStore.sha256Hex(file.readBytes()) == existing.sha256,
                ) { "saved A2A Artifact no longer matches the remote Task snapshot" }
                return@synchronized artifactReference(
                    existing.id,
                    existing.sha256,
                    existing.size,
                    existing.mediaType,
                )
            }
            val parent = requireNotNull(resolveWorkspaceFile(path).parentFile) { "A2A Artifact path has no parent" }
            require((parent.isDirectory || parent.mkdirs()) && parent.isDirectory) {
                "A2A Artifact output directory is unavailable"
            }
            val outcome =
                workspace.writeArtifact(
                    path = path,
                    bytes = bytes,
                    region = WorkspaceLayout.OUTPUT,
                    sessionId = sessionId,
                    sink =
                        WorkspaceArtifactStore.ArtifactSink { owner, record ->
                            storage.artifacts.register(
                                record.id,
                                owner,
                                record.relativePath,
                                record.mediaType,
                                record.sizeBytes,
                                record.sha256,
                                resolveWorkspaceFile(FileScopePath(workspaceScopeId, record.relativePath)),
                            )
                        },
                )
            artifactReference(
                outcome.record.id,
                outcome.record.sha256,
                outcome.record.sizeBytes,
                outcome.record.mediaType,
            )
        }

    private fun artifactReference(
        artifactId: String,
        sha256: String,
        size: Long,
        mediaType: String,
    ): JsonObject =
        buildJsonObject {
            put("artifactRef", artifactId)
            put("sha256", sha256)
            put("size", size)
            put("mediaType", mediaType)
            put("trust", UNTRUSTED_MARKER)
        }

    private fun decodeBounded(base64: String): ByteArray {
        require(
            base64.length <= MAX_BASE64_CHARS && base64.matches(BASE64_PATTERN),
        ) { "A2A artifact base64 is invalid" }
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size.toLong() <= MAX_ARTIFACT_BYTES) { "A2A remote Artifact exceeds import limit" }
        return bytes
    }

    private fun safeFilename(value: String): String {
        val cleaned = value.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(128)
        return cleaned.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "artifact.bin"
    }

    private companion object {
        const val UNTRUSTED_MARKER = "UNTRUSTED_A2A_CONTENT"
        const val MAX_ARTIFACT_BYTES = 1024L * 1024
        const val MAX_BASE64_CHARS = 1_500_000
        val ARTIFACT_IMPORT_LOCK = Any()
        val BASE64_PATTERN = Regex("[A-Za-z0-9+/]*={0,2}")
    }
}
