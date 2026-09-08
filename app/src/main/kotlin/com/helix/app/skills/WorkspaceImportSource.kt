package com.helix.app.skills

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** Copies bounded, containment-checked Workspace bytes into a private validation snapshot. */
internal class WorkspaceImportSource(
    private val store: WorkspaceArtifactStore,
    private val temporaryRoot: Path,
) {
    @Suppress("NestedBlockDepth") // Bounded recursive copying remains inside the unconditional cleanup scope.
    fun <T> capture(
        reference: String,
        cancelled: () -> Boolean,
        use: (Path) -> T,
    ): T {
        val source = FileScopePath.fromModelReference(reference)
        require(
            source.scopeId == "app" && source.relativePath.substringBefore('/') in setOf("input", "work", "output"),
        ) {
            "IMPORT_WORKSPACE_SOURCE_REQUIRED"
        }
        Files.createDirectories(temporaryRoot)
        val root = Files.createTempDirectory(temporaryRoot, "source-")
        val target = root.resolve(source.name)
        try {
            var count = 0
            var total = 0L

            fun copy(
                path: FileScopePath,
                destination: Path,
                depth: Int,
            ) {
                check(!cancelled()) { "IMPORT_CANCELLED" }
                require(depth <= 32 && ++count <= 1024) { "IMPORT_TOO_MANY_FILES" }
                val stat = store.stat(path)
                require(stat.exists && !stat.isSymlink) { "IMPORT_INVALID_SOURCE" }
                if (stat.isDirectory) {
                    Files.createDirectories(destination)
                    val entries = store.listDir(path, 1024)
                    require(!entries.truncated) { "IMPORT_TOO_MANY_FILES" }
                    entries.entries.forEach { name ->
                        copy(
                            FileScopePath(path.scopeId, "${path.relativePath}/$name"),
                            destination.resolve(name),
                            depth + 1,
                        )
                    }
                } else {
                    require(stat.isRegularFile && stat.sizeBytes <= MAX_BYTES) { "IMPORT_TOO_LARGE" }
                    val bytes = ByteArrayOutputStream()
                    var offset = 0L
                    do {
                        check(!cancelled()) { "IMPORT_CANCELLED" }
                        val window = store.readWindow(path, offset, 1024L * 1024)
                        require(window.sizeBytes == stat.sizeBytes) { "IMPORT_SOURCE_CHANGED" }
                        val chunk =
                            window.base64?.let { Base64.getDecoder().decode(it) }
                                ?: window.text.orEmpty().toByteArray(Charsets.UTF_8)
                        total += chunk.size
                        require(total <= MAX_BYTES) { "IMPORT_TOO_LARGE" }
                        bytes.write(chunk)
                        require(window.eof || window.nextOffset > offset) { "IMPORT_SOURCE_CHANGED" }
                        offset = window.nextOffset
                    } while (!window.eof)
                    require(bytes.size().toLong() == stat.sizeBytes) { "IMPORT_SOURCE_CHANGED" }
                    Files.write(destination, bytes.toByteArray())
                }
            }
            copy(source, target, 0)
            check(!cancelled()) { "IMPORT_CANCELLED" }
            return use(target)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
