package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceLayout
import com.helix.core.workspace.resolveFileScopePath
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Re-resolves live permission and containment for every operation. */
internal class NioManualFileBackend(
    private val scopeId: String,
    private val roots: ScopeRootResolver,
    private val workspace: Boolean,
    private val requireWrite: () -> Unit,
) : ManualFileBackend {
    private fun path(
        relative: String,
        mutate: Boolean = false,
    ): Path {
        val ref = FileScopePath(scopeId, relative)
        if (mutate) {
            requireWrite()
            require(!ref.isRoot) { "The storage root cannot be changed" }
            if (workspace) {
                require(
                    ref.relativePath.contains('/') &&
                        WorkspaceLayout.regionOf(ref.relativePath) in WorkspaceLayout.regions,
                ) {
                    "Choose an item inside input, work or output"
                }
            }
        }
        if (workspace) require(!ref.relativePath.startsWith(".helix")) { "Internal workspace state is not a user file" }
        return resolveFileScopePath(ref, roots)
    }

    override fun validateMutation(path: String) {
        path(path, true)
    }

    override fun stat(path: String): ManualFileInfo? {
        val target = path(path)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
        require(directory || Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "Unsupported file type" }
        return ManualFileInfo(directory, if (directory) 0 else Files.size(target))
    }

    override fun children(path: String): List<String> =
        Files.newDirectoryStream(path(path)).use { stream -> stream.map { it.fileName.toString() }.sorted() }

    override fun read(path: String): InputStream = Files.newInputStream(path(path))

    override fun create(
        path: String,
        directory: Boolean,
    ) {
        val target = path(path, true)
        if (directory) Files.createDirectory(target) else Files.createFile(target)
    }

    override fun write(path: String): OutputStream {
        val target = path(path, true)
        val stream = Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        return object : java.io.FilterOutputStream(stream) {
            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) {
                requireWrite()
                if (workspace) {
                    com.helix.core.workspace.WorkspaceQuota.ensureRoom(
                        roots.resolveRoot(scopeId),
                        length.toLong(),
                        com.helix.core.workspace.WorkspaceQuotaPolicy.default.maxWorkspaceBytes,
                    )
                }
                out.write(bytes, offset, length)
            }
        }
    }

    override fun rename(
        path: String,
        destination: String,
    ) {
        Files.move(path(path, true), path(destination, true))
    }

    override fun delete(path: String) {
        Files.delete(path(path, true))
    }
}
