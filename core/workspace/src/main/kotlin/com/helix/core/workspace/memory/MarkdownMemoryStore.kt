package com.helix.core.workspace.memory

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Markdown is canonical. The bounded metadata index is rebuilt from files on every query. */
class MarkdownMemoryStore(
    private val root: Path,
    private val rejectContent: (String) -> Boolean = { false },
) {
    @Synchronized
    fun index(scope: MemoryScope): List<MemoryEntry> {
        val directory = directory(scope, create = false)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return emptyList()
        return Files.newDirectoryStream(directory, "*.md").use { entries ->
            val paths = entries.take(MAX_FILES + 1)
            require(paths.size <= MAX_FILES) { "MEMORY_FILE_LIMIT" }
            paths.sortedBy { it.fileName.toString() }.map { read(scope, it.fileName.toString()) }
        }
    }

    @Synchronized
    fun read(
        scope: MemoryScope,
        name: String,
    ): MemoryEntry {
        val path = file(scope, name, create = false)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "MEMORY_MISSING" }
        val bytes =
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var count = input.read(buffer)
                while (count >= 0) {
                    require(output.size() + count <= MemoryMarkdown.MAX_BYTES) { "MEMORY_TOO_LARGE" }
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
                output.toByteArray()
            }
        require(bytes.size <= MemoryMarkdown.MAX_BYTES) { "MEMORY_TOO_LARGE" }
        val text =
            Charsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        require(!rejectContent(text)) { "MEMORY_SENSITIVE_CONTENT" }
        return MemoryEntry(
            name,
            text,
            MemoryMarkdown.hash(text),
            Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis(),
        )
    }

    @Synchronized
    fun write(
        scope: MemoryScope,
        name: String,
        markdown: String,
        expectedHash: String,
    ): MemoryEntry {
        require(markdown.isNotBlank() && markdown.toByteArray(Charsets.UTF_8).size <= MemoryMarkdown.MAX_BYTES)
        require(!rejectContent(markdown)) { "MEMORY_SENSITIVE_CONTENT" }
        if (scope == MemoryScope.Global) {
            require(!markdown.lineSequence().any { it.trim() == "type: project" }) {
                "PROJECT_MEMORY_REQUIRES_PROJECT_ID"
            }
        }
        val path = file(scope, name, create = true)
        val exists = Files.exists(path, NOFOLLOW_LINKS)
        val previous = if (exists) read(scope, name).hash else "new"
        require(previous == expectedHash) { "MEMORY_CONFLICT_RELOAD" }
        val current = index(scope)
        require(exists || current.size < MAX_FILES) { "MEMORY_FILE_LIMIT" }
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        require(
            current.filter { it.path != name }.sumOf { it.markdown.toByteArray(Charsets.UTF_8).size } + bytes.size <=
                MAX_TOTAL_BYTES,
        )
        val temp = path.resolveSibling(".${java.util.UUID.randomUUID()}.tmp")
        try {
            java.nio.channels.FileChannel.open(temp, CREATE_NEW, WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // A human edit between read and save must not be knowingly overwritten.
            require(
                (
                    if (exists) {
                        read(scope, name).hash
                    } else if (Files.exists(path, NOFOLLOW_LINKS)) {
                        "exists"
                    } else {
                        "new"
                    }
                ) ==
                    expectedHash,
            ) {
                "MEMORY_CONFLICT_RELOAD"
            }
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return read(scope, name)
    }

    @Synchronized
    fun edit(
        scope: MemoryScope,
        name: String,
        expectedHash: String,
        old: String,
        replacement: String,
    ): MemoryEntry {
        val entry = read(scope, name)
        require(entry.hash == expectedHash) { "MEMORY_CONFLICT_RELOAD" }
        require(
            old.isNotEmpty() && entry.markdown.indexOf(old) >= 0 &&
                entry.markdown.indexOf(old) == entry.markdown.lastIndexOf(old),
        ) {
            "MEMORY_EDIT_REQUIRES_UNIQUE_MATCH"
        }
        return write(scope, name, entry.markdown.replace(old, replacement), expectedHash)
    }

    @Synchronized
    fun delete(
        scope: MemoryScope,
        name: String,
        expectedHash: String,
    ) {
        require(read(scope, name).hash == expectedHash) { "MEMORY_CONFLICT_RELOAD" }
        Files.delete(file(scope, name, create = false))
    }

    fun search(
        scope: MemoryScope,
        query: String,
    ): List<MemoryEntry> {
        require(query.length in 1..256)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return index(scope)
            .map { entry -> entry to words.count { entry.markdown.contains(it, ignoreCase = true) } }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt },
            ).take(8)
            .map { it.first }
    }

    private fun file(
        scope: MemoryScope,
        name: String,
        create: Boolean,
    ): Path {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.md"))) { "MEMORY_INVALID_PATH" }
        require(!rejectContent(name)) { "MEMORY_SENSITIVE_CONTENT" }
        val path = directory(scope, create).resolve(name)
        require(!Files.isSymbolicLink(path)) { "MEMORY_LINK_REJECTED" }
        return path
    }

    private fun directory(
        scope: MemoryScope,
        create: Boolean,
    ): Path {
        val relative =
            when (scope) {
                MemoryScope.Global -> listOf("global")
                is MemoryScope.Project -> listOf("projects", scope.key.value)
            }
        var path = root.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(path)) { "MEMORY_LINK_REJECTED" }
        if (create) Files.createDirectories(path)
        for (part in relative) {
            path = path.resolve(part)
            require(!Files.isSymbolicLink(path)) { "MEMORY_LINK_REJECTED" }
            if (create && !Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        }
        return path
    }

    companion object {
        const val MAX_FILES = 128
        const val MAX_TOTAL_BYTES = 1_048_576
    }
}
