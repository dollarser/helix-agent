package com.helix.app.backup

import com.helix.core.storage.content.DocumentReadAccess
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.workspace.AtomicFileWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/** Only additions with a durable, unpredictable owned name can be rolled back.
 * Existing public files are never replaced. */
internal class RestoreLibraryDocuments(
    private val target: DocumentStorage,
    private val journal: File,
) : DocumentStorage by target {
    override fun save(
        directory: String,
        name: String,
        bytes: ByteArray,
        previous: DocumentStorage.File?,
        mimeType: String,
    ): DocumentStorage.File {
        require(previous == null) { "RESTORE_PUBLIC_REPLACEMENT_FORBIDDEN" }
        require(directory in setOf("notes", "sources") || directory.matches(Regex("originals/[A-Za-z0-9_-]{1,100}")))
        val token = "restore-${UUID.randomUUID()}"
        val slot = name.substringBefore('.')
        val extension =
            name
                .substringAfterLast('.', "bin")
                .filter(Char::isLetterOrDigit)
                .take(12)
                .ifEmpty { "bin" }
        val ownedName =
            if (directory.startsWith("originals/") && (slot in (0..7).map(Int::toString) || slot == "public-page")) {
                "$slot.$token.$extension"
            } else {
                "$token.$extension"
            }
        require(bytes.size <= 16 * 1024 * 1024)
        val item =
            JsonObject(
                mapOf(
                    "directory" to JsonPrimitive(directory),
                    "name" to JsonPrimitive(ownedName),
                    "sha256" to JsonPrimitive(DocumentStorage.hash(bytes)),
                ),
            )
        val entries = read() + item
        require(entries.size <= 1024)
        AtomicFileWriter.writeAtomic(journal.toPath(), JsonArray(entries).toString().toByteArray())
        return target.save(directory, ownedName, bytes, mimeType = mimeType)
    }

    override fun delete(previous: DocumentStorage.File): Unit = error("RESTORE_PUBLIC_DELETE_FORBIDDEN")

    fun rollback() {
        read().asReversed().forEach { item ->
            val directory = item.getValue("directory").jsonPrimitive.content
            val name = item.getValue("name").jsonPrimitive.content
            val hash = item.getValue("sha256").jsonPrimitive.content
            require(name.contains(Regex("restore-[a-f0-9-]{36}")))
            val matches =
                target
                    .references(directory)
                    .map { target.read(it, 16 * 1024 * 1024) }
                    .filter { it.name == name || it.name.startsWith("$name.") }
            require(matches.size <= 1 && matches.all { it.hash == hash }) { "RESTORE_PUBLIC_ROLLBACK_CONFLICT" }
            matches.forEach(target::delete)
            (target as? com.helix.core.storage.content.DocumentPendingCleanup)?.discardOwnedPending(directory, name)
        }
        if (journal.exists()) check(journal.delete())
    }

    private fun read(): List<JsonObject> {
        if (!journal.exists()) return emptyList()
        require(journal.length() <= 1024 * 1024)
        val rows = Json.parseToJsonElement(journal.readText()) as JsonArray
        require(rows.size <= 1024)
        return rows.map { value ->
            (value as JsonObject).also {
                require(it.keys == setOf("directory", "name", "sha256"))
            }
        }
    }
}

internal class RestoreLibrarySource(
    private val descriptors: List<JsonObject>,
    private val members: Map<String, File>,
) : DocumentReadAccess {
    override fun list(directory: String): List<DocumentStorage.File> {
        val prefix = "$directory/"
        return descriptors
            .filter {
                val path = it.getValue("path").jsonPrimitive.content
                path.startsWith(prefix) && '/' !in path.removePrefix(prefix)
            }.map { read(it.getValue("path").jsonPrimitive.content, 16 * 1024 * 1024) }
    }

    override fun read(
        reference: String,
        maxBytes: Int,
    ): DocumentStorage.File {
        val descriptor = descriptors.single { it.getValue("path").jsonPrimitive.content == reference }
        val hash = descriptor.getValue("sha256").jsonPrimitive.content
        val file = members.getValue("blobs/$hash")
        require(file.length() <= minOf(maxBytes, 16 * 1024 * 1024))
        val bytes = file.readBytes()
        require(DocumentStorage.hash(bytes) == hash) { "BACKUP_HASH_INVALID" }
        return DocumentStorage.File(reference, reference.substringAfterLast('/'), bytes, hash)
    }
}
