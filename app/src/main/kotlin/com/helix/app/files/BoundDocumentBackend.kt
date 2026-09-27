package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceFileBackend

/** Re-resolves the registered resource and its live grant for each operation. */
internal class BoundDocumentBackend(
    private val scopeId: String,
    private val source: (FileScopePath) -> FileScopePath,
    private val backend: (String) -> WorkspaceFileBackend,
) : WorkspaceFileBackend {
    private fun resolve(path: String) = source(FileScopePath(scopeId, path))

    override fun validateMutation(path: String) =
        resolve(path).let {
            backend(it.scopeId).validateMutation(it.relativePath)
        }

    override fun stat(path: String) = resolve(path).let { backend(it.scopeId).stat(it.relativePath) }

    override fun children(path: String) = resolve(path).let { backend(it.scopeId).children(it.relativePath) }

    override fun read(path: String) = resolve(path).let { backend(it.scopeId).read(it.relativePath) }

    override fun create(
        path: String,
        directory: Boolean,
    ) = resolve(path).let {
        backend(it.scopeId).create(it.relativePath, directory)
    }

    override fun write(path: String) = resolve(path).let { backend(it.scopeId).write(it.relativePath) }

    override fun delete(path: String) = resolve(path).let { backend(it.scopeId).delete(it.relativePath) }

    override fun rename(
        path: String,
        destination: String,
    ) {
        val from = resolve(path)
        val to = resolve(destination)
        require(from.scopeId == to.scopeId)
        backend(from.scopeId).rename(from.relativePath, to.relativePath)
    }
}
