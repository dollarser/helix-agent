package com.helix.core.storage.repository

import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.ArtifactDao
import com.helix.core.storage.entity.ArtifactEntity
import java.io.File

class ArtifactRepository(
    private val dao: ArtifactDao,
) {
    /**
     * Registers an artifact. doc 9.2: the file with its hash must exist first — [file] is
     * always re-verified (existence, size, SHA-256) before the row lands. There is no
     * out-of-band path: an unverified reference row is exactly what this guard exists to
     * prevent.
     */
    fun register(
        id: String,
        sessionId: String,
        relativePath: String,
        mediaType: String,
        size: Long,
        sha256: String,
        file: File,
    ): ArtifactEntity {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/")) {
            "relativePath must be a non-blank relative path"
        }
        require(mediaType.isNotBlank()) { "mediaType must not be blank" }
        require(size >= 0) { "size must be >= 0" }
        require(sha256.length == 64) { "sha256 must be a hex string" }
        require(file.isFile) { "artifact file not found: $relativePath" }
        require(file.length() == size) { "artifact file size mismatch for $relativePath" }
        require(FileContentStore.sha256Hex(file) == sha256) {
            "artifact file hash mismatch for $relativePath"
        }
        val entity = ArtifactEntity(id, sessionId, relativePath, mediaType, size, sha256)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ArtifactEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("artifact not found: $id")
    }

    fun listBySession(sessionId: String): List<ArtifactEntity> = dao.listBySession(sessionId)

    fun findBySessionAndPath(
        sessionId: String,
        relativePath: String,
    ): ArtifactEntity? = dao.bySessionAndPath(sessionId, relativePath)
}
