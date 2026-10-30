package com.helix.app.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

/** Private immutable blob staging, deduplicated across attachments, knowledge and workspace selections. */
internal class PortableFileSet(
    private val root: File,
    private val checkActive: () -> Unit,
) {
    private val entries = linkedMapOf<String, JsonObject>()
    private val blobs = linkedMapOf<String, File>()
    private var bytes = 0L
    val metadata get() = JsonArray(entries.values.toList())
    val archiveFiles get() = blobs.toMap()

    fun add(
        group: String,
        path: String,
        source: File,
        expectedHash: String? = null,
    ): JsonObject {
        require(group in GROUPS)
        safePath(path)
        require(Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS)) { "BACKUP_SOURCE_NOT_REGULAR" }
        require(entries.size < 20_000)
        val temporary = File.createTempFile("blob-", ".pending", root)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    size = copyBlob(input, output, digest)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(expectedHash == null || hash == expectedHash) { "BACKUP_SOURCE_CHANGED" }
            val key = "$group/$path"
            val descriptor =
                buildJsonObject {
                    put("group", group)
                    put("path", path)
                    put("sha256", hash)
                    put("size", size)
                }
            val previous = entries[key]
            require(previous == null || previous == descriptor) { "BACKUP_SOURCE_CHANGED" }
            if ("blobs/$hash" !in blobs) {
                require(size <= 2L * 1024 * 1024 * 1024 - bytes) { "BACKUP_TOTAL_LIMIT" }
                val destination = File(root, hash)
                Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE)
                blobs["blobs/$hash"] = destination
                bytes += size
            }
            entries[key] = descriptor
            return descriptor
        } finally {
            temporary.delete()
        }
    }

    private fun copyBlob(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        digest: MessageDigest,
    ): Long {
        var size = 0L
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0 && count <= 512L * 1024 * 1024 - size) { "BACKUP_FILE_LIMIT" }
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            size += count
        }
        return size
    }

    fun addBytes(
        group: String,
        path: String,
        source: ByteArray,
    ): JsonObject {
        val temporary = File.createTempFile("data-", ".pending", root)
        try {
            temporary.writeBytes(source)
            return add(group, path, temporary)
        } finally {
            temporary.delete()
        }
    }

    fun tree(
        group: String,
        directory: File,
        prefix: String = "",
    ) {
        if (!directory.exists()) return
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        Files.walk(directory.toPath(), 32).use { stream ->
            stream.sorted().forEach { path ->
                checkActive()
                require(!Files.isSymbolicLink(path)) { "BACKUP_SOURCE_SYMLINK" }
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    val relative = directory.toPath().relativize(path).toString()
                    add(group, if (prefix.isEmpty()) relative else "$prefix/$relative", path.toFile())
                } else {
                    require(Files.isDirectory(path, NOFOLLOW_LINKS))
                    require(directory.toPath().relativize(path).nameCount < 32) { "BACKUP_DEPTH_LIMIT" }
                }
            }
        }
    }

    companion object {
        val GROUPS =
            setOf(
                "content",
                "memory",
                "skills",
                "plans",
                "marketplaces",
                "history",
                "network",
                "attachment",
                "workspace",
                "instructions",
                "library",
            )

        fun safePath(path: String) {
            require(
                path.length in 1..1024 && !path.startsWith('/') && !path.contains('\\') &&
                    path.none { it.isISOControl() },
            )
            require(path.split('/').none { it in setOf("", ".", "..") })
        }

        fun validate(
            metadata: JsonArray,
            files: Map<String, File>,
        ): Map<String, List<JsonObject>> {
            require(metadata.size <= 20_000)
            val seen = mutableSetOf<String>()
            val groups = linkedMapOf<String, MutableList<JsonObject>>()
            metadata.forEach { value ->
                val row = value as JsonObject
                require(row.keys == setOf("group", "path", "sha256", "size"))
                val group = row.getValue("group").jsonPrimitive.content
                val path = row.getValue("path").jsonPrimitive.content
                require(group in GROUPS && seen.add("$group/$path"))
                safePath(path)
                val hash = row.getValue("sha256").jsonPrimitive.content
                require(hash.matches(Regex("[0-9a-f]{64}")))
                require(!row.getValue("size").jsonPrimitive.isString)
                require(files.getValue("blobs/$hash").length() == row.getValue("size").jsonPrimitive.long)
                groups.getOrPut(group) { mutableListOf() } += row
            }
            return groups
        }
    }
}
