package com.helix.core.storage.input
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Replaceable per-session input cache, NOT a Room aggregate or a send prerequisite.
 * The application owns one instance. Edit revisions order user edits, not database transactions.
 * A failed file write retains the latest in-memory input; callers may still submit that input.
 */
@Suppress("TooManyFunctions") // One synchronized cache owner keeps ordering, bounded I/O and cleanup together.
class ComposerInputCache(
    private val directory: File,
) {
    private val current = mutableMapOf<String, ComposerInputSnapshot?>()
    private val latestRevision = mutableMapOf<String, Long>()

    @Synchronized
    fun get(sessionId: String): ComposerInputSnapshot? {
        require(sessionId.isNotBlank())
        if (current.containsKey(sessionId)) return current[sessionId]
        val value = readFile(sessionId)
        current[sessionId] = value
        value?.let { latestRevision[sessionId] = it.revision }
        return value
    }

    /** New user input replaces the prior cache; only a late older writer is ignored. */
    @Synchronized
    fun save(draft: ComposerInputSnapshot): Boolean {
        require(draft.sessionId.isNotBlank() && draft.clientRequestId.isNotBlank() && draft.revision >= 0)
        get(draft.sessionId)
        val latest = latestRevision[draft.sessionId]
        val olderWrite = latest != null && draft.revision < latest
        val alreadyRemoved = draft.revision == latest && current[draft.sessionId] == null
        if (olderWrite || alreadyRemoved) {
            return false
        }
        latestRevision[draft.sessionId] = draft.revision
        val empty =
            draft.text.isEmpty() && draft.attachmentIdsJson == "[]" &&
                draft.revisedMessageId == null && draft.referenceSourceSessionId == null
        current[draft.sessionId] = if (empty) null else draft
        return if (empty) deleteFile(draft.sessionId) else writeFile(draft)
    }

    /** An accepted older message must not delete the user's next input. */
    @Synchronized
    @Suppress("ReturnCount") // Empty, newer-input and matching-cache cleanup have different outcomes.
    fun clear(
        sessionId: String,
        revision: Long,
        requestId: String,
    ): Boolean {
        val value = get(sessionId)
        if (value == null) {
            latestRevision[sessionId] = maxOf(latestRevision[sessionId] ?: -1, revision)
            return deleteFile(sessionId)
        }
        if (value.revision > revision ||
            (value.revision == revision && value.clientRequestId != requestId)
        ) {
            return false
        }
        latestRevision[sessionId] = maxOf(latestRevision[sessionId] ?: -1, revision)
        current[sessionId] = null
        return deleteFile(sessionId)
    }

    /** Explicit session removal also removes its input cache, independently of Room. */
    @Synchronized
    fun removeSession(sessionId: String): Boolean {
        get(sessionId)
        current[sessionId] = null
        latestRevision[sessionId] = Long.MAX_VALUE
        return deleteFile(sessionId)
    }

    @Suppress("SwallowedException") // A missing/corrupt optional cache cannot disable the editor.
    private fun readFile(sessionId: String): ComposerInputSnapshot? =
        try {
            val file = fileFor(sessionId)
            if (!file.isFile || file.length() > MAX_BYTES) {
                null
            } else {
                readBounded(file)?.let { decode(it, sessionId) }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun readBounded(file: File): String? =
        file.inputStream().use { input ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count > MAX_BYTES) null else String(bytes, 0, count, Charsets.UTF_8)
        }

    @Suppress("SwallowedException") // False records cache failure; it never represents send failure.
    private fun writeFile(draft: ComposerInputSnapshot): Boolean {
        val bytes = encode(draft).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        var temporary: File? = null
        return try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Input cache directory unavailable")
            temporary = File.createTempFile("input-", ".tmp", directory)
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                fileFor(draft.sessionId).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } finally {
            try {
                temporary?.delete()
            } catch (_: SecurityException) {
                // Stale temporary data is never treated as the accepted input file.
            }
        }
    }

    @Suppress("SwallowedException")
    private fun deleteFile(sessionId: String): Boolean =
        try {
            val file = fileFor(sessionId)
            !file.exists() || file.delete()
        } catch (_: SecurityException) {
            false
        }

    private fun fileFor(sessionId: String): File {
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(sessionId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }

    private fun encode(draft: ComposerInputSnapshot): String =
        buildJsonObject {
            put("sessionId", draft.sessionId)
            put("revision", draft.revision)
            put("clientRequestId", draft.clientRequestId)
            put("text", draft.text)
            put("attachmentIds", Json.parseToJsonElement(draft.attachmentIdsJson))
            put("revisedMessageId", draft.revisedMessageId)
            put("delivery", draft.delivery)
            put("expectedTurnId", draft.expectedTurnId)
            put("referenceSourceSessionId", draft.referenceSourceSessionId)
            put("referenceKind", draft.referenceKind)
        }.toString()

    private fun decode(
        text: String,
        sessionId: String,
    ): ComposerInputSnapshot {
        val item = requireNotNull(Json.parseToJsonElement(text) as? JsonObject)

        fun required(key: String): String = requireNotNull((item[key] as? JsonPrimitive)?.contentOrNull)

        fun optional(key: String): String? = (item[key] as? JsonPrimitive)?.contentOrNull
        require(required("sessionId") == sessionId)
        val revision = required("revision").toLong()
        require(revision in 0..(Long.MAX_VALUE / 2) && required("clientRequestId").isNotBlank())
        val attachments = requireNotNull(item["attachmentIds"] as? JsonArray)
        require(attachments.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() })
        require(required("delivery") in setOf("QUEUE", "STEER"))
        require(optional("referenceKind") in setOf(null, "SUMMARY", "RECENT_MESSAGES"))
        require((optional("referenceSourceSessionId") == null) == (optional("referenceKind") == null))
        require(optional("referenceSourceSessionId") != sessionId)
        return ComposerInputSnapshot(
            sessionId,
            revision,
            required("clientRequestId"),
            required("text"),
            attachments.toString(),
            optional("revisedMessageId"),
            required("delivery"),
            optional("expectedTurnId"),
            optional("referenceSourceSessionId"),
            optional("referenceKind"),
        )
    }

    companion object {
        private const val MAX_BYTES = 1_048_576
    }
}
