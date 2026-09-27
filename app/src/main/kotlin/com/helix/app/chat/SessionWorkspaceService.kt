package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.resolveFileScopePath
import java.nio.file.Path

/** Application boundary for user-selected resources. Registration does not grant tool permission. */
internal class SessionWorkspaceService(
    private val storage: HelixStorage,
    private val roots: ScopeRootResolver,
    private val safIdentity: (FileScopePath) -> String,
) {
    fun bind(reference: String): String {
        val path = FileScopePath.fromModelReference(reference)
        if (storage.workspaces.find(path.scopeId) != null) {
            val resource = requireNotNull(storage.workspaces.find(path.scopeId))
            if (resource.backend == "PATH") {
                val root = resolveRoot(path.scopeId)
                storage.workspaces.directoryWitness(resolveFileScopePath(path, ScopeRootResolver { root }))
            } else {
                safIdentity(source(path))
            }
            return path.toModelReference()
        }
        val saf = path.scopeId.startsWith("saf-")
        val witness =
            if (saf) {
                safIdentity(
                    path,
                )
            } else {
                storage.workspaces.directoryWitness(resolveFileScopePath(path, roots))
            }
        val resource =
            storage.workspaces.register(
                if (saf) "SAF" else "PATH",
                path.toModelReference(),
                witness,
                System.currentTimeMillis(),
                identityLocator = if (saf) witness else resolveFileScopePath(path, roots).toRealPath().toString(),
            )
        return FileScopePath(resource.id, "").toModelReference()
    }

    @Suppress("ReturnCount") // Legacy, managed and external backends resolve independently.
    fun resolveRoot(scopeId: String): Path {
        val resource = storage.workspaces.find(scopeId) ?: return roots.resolveRoot(scopeId)
        require(resource.availability == "READY") { "Workspace unavailable; explicit rebind required" }
        if (resource.backend !=
            "PATH"
        ) {
            throw ScopeNotAvailable("This workspace backend does not provide a local filesystem directory")
        }
        if (resource.ownership == "MANAGED") return storage.workspaces.managedDirectory(scopeId)
        return verify(resource.id) {
            val root = resolveFileScopePath(FileScopePath.fromModelReference(resource.locator), roots)
            require(storage.workspaces.directoryWitness(root) == resource.witness) { "Workspace identity changed" }
            root
        }
    }

    /** SAF keeps the same document-tree adapter and live grants; never manufacture a POSIX path. */
    @Suppress("ReturnCount") // Managed and legacy sources do not require external translation.
    fun source(path: FileScopePath): FileScopePath {
        val resource = storage.workspaces.find(path.scopeId) ?: return path
        require(resource.availability == "READY") { "Workspace unavailable" }
        if (resource.ownership == "MANAGED") return path
        val root = FileScopePath.fromModelReference(resource.locator)
        if (resource.backend == "SAF") {
            verify(resource.id) { require(safIdentity(root) == resource.witness) { "Workspace document changed" } }
        }
        val relative = listOf(root.relativePath, path.relativePath).filter(String::isNotEmpty).joinToString("/")
        return FileScopePath(root.scopeId, relative)
    }

    // Record observed loss; every failure is rethrown, never treated as success.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <T> verify(
        id: String,
        operation: () -> T,
    ): T =
        try {
            operation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            storage.workspaces.markUnavailable(id)
            throw ScopeNotAvailable("Workspace unavailable; select the source again to bind a new resource")
        }
}
