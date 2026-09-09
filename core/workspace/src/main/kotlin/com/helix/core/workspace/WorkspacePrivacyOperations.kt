package com.helix.core.workspace

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class WorkspacePrivacyOperations(
    private val resolve: (String) -> Path,
    private val resolveContained: (FileScopePath, Path) -> Path,
    private val ensureLayout: (String) -> Unit,
) {
    /**
     * Irreversible user-confirmed privacy deletion. This is intentionally not a model Tool path:
     * it accepts the same contained [FileScopePath], rejects directories, and removes exactly one
     * file whose artifact rows have already been erased by the caller.
     */
    fun deletePermanentlyForPrivacy(path: FileScopePath): Boolean {
        val root = resolve(path.scopeId)
        val target = resolveContained(path, root)
        val privateArtifact =
            path.relativePath.startsWith(".helix/subscription-results/") ||
                path.relativePath.startsWith(".helix/goal-evidence/")
        require(WorkspaceLayout.regionOf(path.relativePath) in WorkspaceLayout.regions || privateArtifact) {
            "privacy deletion is limited to workspace data regions and owned artifact directories"
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "privacy deletion target must be a file" }
        Files.delete(target)
        return true
    }

    /** Clears every fixed workspace region after an explicit user confirmation. */
    fun clearForPrivacy(scope: String) {
        val root = resolve(scope)
        listOf(WorkspaceLayout.INPUT, WorkspaceLayout.WORK, WorkspaceLayout.OUTPUT, WorkspaceLayout.HELIX)
            .forEach { region ->
                val directory = PathResolution.join(root, region)
                if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) deleteTreeForPrivacy(directory)
            }
        ensureLayout(scope)
    }

    private fun deleteTreeForPrivacy(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
