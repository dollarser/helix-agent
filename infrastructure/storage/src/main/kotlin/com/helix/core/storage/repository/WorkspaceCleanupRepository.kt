package com.helix.core.storage.repository

import com.helix.core.storage.dao.WorkspaceDao
import com.helix.core.storage.entity.WorkspaceEntity
import com.helix.core.workspace.WorkspaceDirectoryIdentity
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Explicit, resumable cleanup of an unreferenced app-owned directory. The application holds
 * execution and manual-file admission throughout this call. No startup path invokes cleanup.
 * Room fences new references before filesystem work; phase changes never assert an FS rollback.
 */
class WorkspaceCleanupRepository(
    private val dao: WorkspaceDao,
    private val managedRoot: Path,
    private val directoryIdentity: (Path) -> String = WorkspaceDirectoryIdentity::witness,
    private val transaction: (() -> Unit) -> Unit,
) {
    fun cleanup(
        id: String,
        backupsRetained: () -> Boolean,
    ) {
        val resource = fence(id, backupsRetained)
        if (resource.availability == "DELETED") return
        val original = managedRoot.resolve(id)
        val quarantineRoot = managedRoot.resolveSibling("managed-cleanup")
        val quarantined = quarantineRoot.resolve(id)
        var phase = requireNotNull(dao.find(id)).availability
        if (phase == "CLEANUP_FENCED") {
            Files.createDirectories(quarantineRoot)
            if (!Files.exists(quarantined, LinkOption.NOFOLLOW_LINKS)) {
                verify(original, resource)
                // Same private filesystem only. No copy/delete fallback on unsupported atomic rename.
                Files.move(original, quarantined, StandardCopyOption.ATOMIC_MOVE)
            }
            verify(quarantined, resource)
            check(!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) { "Original path was recreated; review required" }
            transition(id, "CLEANUP_FENCED", "CLEANUP_QUARANTINED")
            phase = "CLEANUP_QUARANTINED"
        }
        if (phase == "CLEANUP_QUARANTINED") {
            verify(quarantined, resource)
            transition(id, "CLEANUP_QUARANTINED", "CLEANUP_PURGING")
        }
        if (Files.exists(quarantined, LinkOption.NOFOLLOW_LINKS)) {
            verify(quarantined, resource)
            purgeOwnedTree(quarantined)
        }
        transition(id, "CLEANUP_PURGING", "DELETED")
    }

    private fun fence(
        id: String,
        backupsRetained: () -> Boolean,
    ): WorkspaceEntity {
        var result: WorkspaceEntity? = null
        transaction {
            val resource = requireNotNull(dao.find(id)) { "Unknown workspace" }
            require(resource.ownership == "MANAGED" && resource.backend == "PATH") { "External resources are retained" }
            require(resource.locator == id && id.matches(Regex("ws-[a-f0-9-]{36}")))
            require(
                !dao.retentionReferences(id, "scope:$id:").retained,
            ) { "Workspace references must be released first" }
            require(!backupsRetained()) { "Workspace backups require explicit resolution" }
            require(resource.availability in PHASES) { "Workspace identity requires review before cleanup" }
            if (resource.availability == "READY") {
                verify(managedRoot.resolve(id), resource)
                transition(id, "READY", "CLEANUP_FENCED")
            }
            result = resource
        }
        return requireNotNull(result)
    }

    private fun transition(
        id: String,
        expected: String,
        next: String,
    ) {
        check(dao.compareAvailability(id, expected, next) == 1) { "Workspace cleanup state changed" }
    }

    private fun verify(
        path: Path,
        resource: WorkspaceEntity,
    ) {
        check(
            directoryIdentity(path) == resource.witness,
        ) { "Workspace identity changed; files retained" }
    }

    private fun purgeOwnedTree(root: Path) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // walkFileTree does not follow links; unlinking a child symlink never deletes its target.
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        val PHASES = setOf("READY", "CLEANUP_FENCED", "CLEANUP_QUARANTINED", "CLEANUP_PURGING", "DELETED")
    }
}
