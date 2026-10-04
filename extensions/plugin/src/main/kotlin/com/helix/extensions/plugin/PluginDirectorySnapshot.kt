package com.helix.extensions.plugin

import java.io.InterruptedIOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

/** Bounded package bytes, not an executable directory or a way to traverse Workspace permissions. */
internal object PluginDirectorySnapshot {
    fun read(
        root: Path,
        cancelled: () -> Boolean,
    ): Map<String, ByteArray> {
        check(!cancelled()) { "IMPORT_CANCELLED" }
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "PLUGIN_DIRECTORY_REQUIRED" }
        require(Files.isRegularFile(root.resolve("plugin.json"), NOFOLLOW_LINKS)) { "PLUGIN_MANIFEST_REQUIRED" }
        val files = linkedMapOf<String, ByteArray>()
        var entries = 0
        var total = 0L
        Files.walk(root, MAX_DEPTH + 1).use { paths ->
            paths.filter { it != root }.forEach { path ->
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Plugin read cancelled")
                check(!cancelled()) { "IMPORT_CANCELLED" }
                val relative = root.relativize(path)
                require(++entries <= MAX_ENTRIES && relative.nameCount <= MAX_DEPTH) { "CONNECTOR_TOO_MANY_FILES" }
                require(!Files.isSymbolicLink(path)) { "CONNECTOR_SPECIAL_FILE" }
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "CONNECTOR_SPECIAL_FILE" }
                    val name =
                        PluginPackageReader.safePath(
                            relative.toString().replace(java.io.File.separatorChar, '/'),
                        )
                    val bytes = readFile(path)
                    total += bytes.size
                    require(total <= PluginPackageReader.MAX_BYTES) { "CONNECTOR_TOO_LARGE" }
                    files[name] = bytes
                }
            }
        }
        check(!cancelled()) { "IMPORT_CANCELLED" }
        return files
    }

    private fun readFile(path: Path): ByteArray =
        Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            PluginPackageReader.readBounded(Channels.newInputStream(channel), PluginPackageReader.MAX_FILE_BYTES)
        }

    private const val MAX_DEPTH = 32
    private const val MAX_ENTRIES = 1024
}
