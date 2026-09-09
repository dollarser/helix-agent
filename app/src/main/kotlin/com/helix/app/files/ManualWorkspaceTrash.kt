package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceLayout
import com.helix.core.workspace.resolveFileScopePath
import java.nio.file.Files
import java.util.UUID

/** Directory trash for the manual manager; the Agent's regular-file trash API is unchanged. */
internal class ManualWorkspaceTrash(
    private val roots: ScopeRootResolver,
    private val scope: String,
) {
    private fun path(relative: String) = resolveFileScopePath(FileScopePath(scope, relative), roots)

    fun isDirectory(relative: String): Boolean = Files.isDirectory(path(relative))

    fun trash(relative: String) {
        require(relative.contains('/') && WorkspaceLayout.regionOf(relative) in WorkspaceLayout.regions)
        val encoded = relative.replace("%", "%25").replace("/", "%2F")
        val name = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}__$encoded"
        Files.move(path(relative), path("${WorkspaceLayout.TRASH}/$name"))
    }

    fun restore(
        entry: String,
        original: String,
    ) {
        require(
            !entry.contains('/') && original.contains('/') &&
                WorkspaceLayout.regionOf(original) in WorkspaceLayout.regions,
        )
        val target = path(original)
        if (Files.exists(target)) throw java.nio.file.FileAlreadyExistsException(original)
        Files.createDirectories(target.parent)
        Files.move(path("${WorkspaceLayout.TRASH}/$entry"), target)
    }

    fun purge(entry: String) {
        require(!entry.contains('/'))
        remove("${WorkspaceLayout.TRASH}/$entry")
    }

    private fun remove(relative: String) {
        val target = path(relative)
        if (Files.isDirectory(target)) {
            Files.newDirectoryStream(target).use { children ->
                children.forEach { remove("$relative/${it.fileName}") }
            }
        }
        Files.delete(target)
    }
}
