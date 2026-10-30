package com.helix.core.storage.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Caller-owned private staging; archive names never select a filesystem destination. */
data class AppBackupLimits(
    val contentBytes: Long = 2L * 1024 * 1024 * 1024,
    val fileBytes: Long = 512L * 1024 * 1024,
    val metadataBytes: Long = 64L * 1024 * 1024,
    val manifestBytes: Int = 4 * 1024 * 1024,
    val entries: Int = 20_000,
) {
    val archiveBytes: Long get() = contentBytes + metadataBytes + manifestBytes + 16L * 1024 * 1024

    init {
        require(contentBytes > 0 && fileBytes > 0 && metadataBytes > 0 && manifestBytes > 0 && entries > 0)
    }
}

class AppBackupArchive(
    private val limits: AppBackupLimits = AppBackupLimits(),
) {
    fun write(
        files: Map<String, File>,
        output: OutputStream,
        checkActive: () -> Unit,
    ) {
        require(files.keys.contains("state.json") && files.size <= limits.entries)
        var content = 0L
        var metadata = 0L
        val descriptions =
            files.map { (name, file) ->
                require(validName(name) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                val size = file.length()
                require(size <= limit(name))
                if (name.startsWith("blobs/")) content += size else metadata += size
                require(content <= limits.contentBytes && metadata <= limits.metadataBytes)
                val digest = hash(file, checkActive)
                require(
                    !name.startsWith("blobs/") || name.removePrefix("blobs/") == digest,
                ) { "BACKUP_BLOB_IDENTITY_INVALID" }
                buildJsonObject {
                    put("path", name)
                    put("size", size)
                    put("sha256", digest)
                }
            }
        val manifest =
            buildJsonObject {
                put("format", 1)
                put("product", "helix")
                put("entries", JsonArray(descriptions))
            }.toString().toByteArray()
        require(manifest.size <= limits.manifestBytes)
        ZipOutputStream(LimitedBackupOutput(output, limits.archiveBytes)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            descriptions.forEach { descriptor ->
                checkActive()
                val name = descriptor.getValue("path").jsonPrimitive.content
                zip.putNextEntry(ZipEntry(name))
                files.getValue(name).inputStream().use { input ->
                    val observed = copy(input, zip, limit(name), checkActive)
                    check(observed.first == descriptor.getValue("size").jsonPrimitive.long)
                    check(
                        observed.second == descriptor.getValue("sha256").jsonPrimitive.content,
                    ) { "BACKUP_SOURCE_CHANGED" }
                }
                zip.closeEntry()
            }
        }
    }

    /** All members stay in an exclusively created private directory until every hash and shape passes. */
    fun read(
        input: InputStream,
        staging: File,
        checkActive: () -> Unit,
    ): Map<String, File> {
        require(!staging.exists() && staging.mkdirs())
        val files = linkedMapOf<String, File>()
        var content = 0L
        var metadata = 0L
        val bounded = LimitedBackupInput(input, limits.archiveBytes)
        ZipInputStream(bounded).use { zip ->
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && (name == "manifest.json" || validName(name)))
                require(name !in files && files.size <= limits.entries)
                val file = File(staging, files.size.toString())
                val maximum = remainingLimit(name, content, metadata)
                val observed = file.outputStream().use { copy(zip, it, maximum, checkActive) }
                if (name.startsWith("blobs/")) {
                    content += observed.first
                } else if (name != "manifest.json") {
                    metadata += observed.first
                }
                files[name] = file
                zip.closeEntry()
            }
            // Include the central directory and any trailer in the compressed input budget.
            val buffer = ByteArray(8192)
            while (bounded.read(buffer) >= 0) checkActive()
        }
        validateManifest(files, checkActive)
        return files
    }

    private fun remainingLimit(
        name: String,
        content: Long,
        metadata: Long,
    ): Long =
        if (name == "manifest.json") {
            limits.manifestBytes.toLong()
        } else {
            minOf(
                limit(name),
                if (name.startsWith("blobs/")) {
                    limits.contentBytes - content
                } else {
                    limits.metadataBytes -
                        metadata
                },
            )
        }

    private fun validateManifest(
        files: MutableMap<String, File>,
        checkActive: () -> Unit,
    ) {
        val manifestFile = requireNotNull(files.remove("manifest.json"))
        val manifest = Json.parseToJsonElement(manifestFile.readText()) as JsonObject
        require(manifest.keys == setOf("format", "product", "entries"))
        require(
            !manifest.getValue("format").jsonPrimitive.isString && manifest.getValue("format").jsonPrimitive.long == 1L,
        )
        require(manifest.getValue("product").jsonPrimitive.content == "helix")
        val entries = manifest.getValue("entries") as JsonArray
        require(entries.size == files.size && files.containsKey("state.json"))
        val seen = mutableSetOf<String>()
        entries.forEach { item ->
            val descriptor = item as JsonObject
            require(descriptor.keys == setOf("path", "size", "sha256"))
            val name = descriptor.getValue("path").jsonPrimitive.content
            require(seen.add(name))
            require(
                !name.startsWith("blobs/") ||
                    name.removePrefix("blobs/") == descriptor.getValue("sha256").jsonPrimitive.content,
            ) {
                "BACKUP_BLOB_IDENTITY_INVALID"
            }
            val file = files.getValue(name)
            require(!descriptor.getValue("size").jsonPrimitive.isString)
            require(file.length() == descriptor.getValue("size").jsonPrimitive.long)
            check(
                hash(file, checkActive) == descriptor.getValue("sha256").jsonPrimitive.content,
            ) { "BACKUP_HASH_INVALID" }
        }
    }

    private fun limit(name: String): Long =
        when (name) {
            "state.json" -> limits.metadataBytes
            "credentials.enc" -> 2L * CredentialBackupCipher.MAX_PLAINTEXT
            else -> limits.fileBytes
        }

    private fun validName(name: String): Boolean =
        name in setOf("state.json", "credentials.enc") ||
            name.matches(Regex("blobs/[0-9a-f]{64}"))

    private fun copy(
        input: InputStream,
        output: OutputStream,
        maximum: Long,
        checkActive: () -> Unit,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            checkActive()
            val size = input.read(buffer)
            if (size < 0) break
            require(size > 0 && size <= maximum - count) { "BACKUP_SIZE_LIMIT" }
            output.write(buffer, 0, size)
            digest.update(buffer, 0, size)
            count += size
        }
        return count to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val discard =
        object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) = Unit
        }

    private fun hash(
        file: File,
        checkActive: () -> Unit,
    ): String =
        file.inputStream().use {
            copy(it, discard, Long.MAX_VALUE, checkActive).second
        }
}

private class LimitedBackupInput(
    private val input: InputStream,
    private val maximum: Long,
) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val value = input.read()
        if (value >= 0) require(++count <= maximum) { "BACKUP_SIZE_LIMIT" }
        return value
    }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val size = input.read(bytes, offset, length)
        if (size > 0) {
            count += size
            require(count <= maximum) { "BACKUP_SIZE_LIMIT" }
        }
        return size
    }

    override fun close() = input.close()
}

private class LimitedBackupOutput(
    private val output: OutputStream,
    private val maximum: Long,
) : OutputStream() {
    private var count = 0L

    override fun write(value: Int) {
        require(++count <= maximum) { "BACKUP_SIZE_LIMIT" }
        output.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(length <= maximum - count) { "BACKUP_SIZE_LIMIT" }
        output.write(bytes, offset, length)
        count += length
    }

    override fun close() = output.close()
}
