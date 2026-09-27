package com.helix.core.storage.repository

import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.ArtifactDao
import com.helix.core.storage.entity.ArtifactEntity
import java.io.File

class ArtifactRepository(
    private val dao: ArtifactDao,
    private val writeReference: (String, () -> Unit) -> Unit = { _, write -> write() },
) {
    /** Re-verify a document/backend stream without inventing a local path or copying the project. */
    fun registerOrRefreshStream(
        entity: ArtifactEntity,
        open: () -> java.io.InputStream,
    ): ArtifactEntity {
        require(entity.relativePath.startsWith("scope:") && entity.mediaType.isNotBlank() && entity.size >= 0)
        require(Regex("[a-f0-9]{64}").matches(entity.sha256))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        open().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0) { "Artifact reader made no progress" }
                size = Math.addExact(size, count.toLong())
                require(size <= entity.size) { "Artifact grew during verification" }
                digest.update(buffer, 0, count)
            }
        }
        require(size == entity.size && digest.digest().joinToString("") { "%02x".format(it) } == entity.sha256) {
            "Artifact changed before registration"
        }
        writeReference(entity.relativePath) {
            val refreshed =
                dao.refreshBySessionAndPath(
                    entity.sessionId,
                    entity.relativePath,
                    entity.mediaType,
                    entity.size,
                    entity.sha256,
                    entity.turnId,
                )
            if (refreshed == 0) dao.insertOrIgnore(entity)
        }
        return requireNotNull(dao.bySessionAndPath(entity.sessionId, entity.relativePath))
    }

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
        verifyBeforeRegistration(relativePath, mediaType, size, sha256, file)
        val entity = ArtifactEntity(id, sessionId, relativePath, mediaType, size, sha256)
        writeReference(relativePath) { dao.insert(entity) }
        return entity
    }

    /**
     * Tool-write registration (v15, doc 02 §8/§9.2): same file-first verification as [register],
     * but the row is stable per (sessionId, relativePath) — a re-write of the same path
     * refreshes the existing row's content columns and [turnId] while keeping its id, so
     * message attachments bound to the artifact survive (and their boundSha256 fails closed on
     * the changed content). [turnId] is the turn that wrote the file; null when the writer had
     * no turn context.
     */
    @Suppress("LongParameterList") // one parameter per artifacts column; mirrors register plus turnId
    fun registerOrRefresh(
        id: String,
        sessionId: String,
        relativePath: String,
        mediaType: String,
        size: Long,
        sha256: String,
        turnId: String?,
        file: File,
    ): ArtifactEntity {
        verifyBeforeRegistration(relativePath, mediaType, size, sha256, file)
        // Refresh the stable row first and insert only when no row exists yet: the
        // single-statement upsert (ON CONFLICT ... DO UPDATE) that would do both needs
        // SQLite 3.24, and minSdk 29 AOSP images ship 3.22.
        writeReference(relativePath) {
            val refreshed = dao.refreshBySessionAndPath(sessionId, relativePath, mediaType, size, sha256, turnId)
            if (refreshed == 0) {
                dao.insertOrIgnore(ArtifactEntity(id, sessionId, relativePath, mediaType, size, sha256, turnId))
            }
        }
        return requireNotNull(dao.bySessionAndPath(sessionId, relativePath)) {
            "artifact row missing after registration: $relativePath"
        }
    }

    /**
     * doc 9.2: the file with its hash must exist first — [file] is
     * always re-verified (existence, size, SHA-256) before the row lands. There is no
     * out-of-band path: an unverified reference row is exactly what this guard exists to
     * prevent.
     */
    private fun verifyBeforeRegistration(
        relativePath: String,
        mediaType: String,
        size: Long,
        sha256: String,
        file: File,
    ) {
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
    }

    fun resolve(id: String): ArtifactEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("artifact not found: $id")
    }

    fun listBySession(sessionId: String): List<ArtifactEntity> = dao.listBySession(sessionId)

    /** Own the existing reference in a fork; no file is copied or newly verified. Materialization rechecks its hash. */
    fun copyReference(
        sourceId: String,
        id: String,
        sessionId: String,
    ): ArtifactEntity {
        val source = resolve(sourceId)
        val copied = source.copy(id = id, sessionId = sessionId, turnId = null)
        writeReference(copied.relativePath) { dao.insert(copied) }
        return copied
    }

    /** The turn's own artifact rows by real ownership (HXA-202); no cross-session truncation. */
    fun listByTurn(turnId: String): List<ArtifactEntity> = dao.listByTurn(turnId)

    /** Cross-session newest-first listing (artifact center files section). */
    fun recent(limit: Int): List<ArtifactEntity> = dao.recent(limit)

    fun findBySessionAndPath(
        sessionId: String,
        relativePath: String,
    ): ArtifactEntity? = dao.bySessionAndPath(sessionId, relativePath)
}
