package com.helix.core.storage.knowledge

import com.helix.core.storage.content.DocumentReadAccess
import com.helix.core.storage.content.DocumentStorage
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/** Explicit current-format import, not a linked second authority or a legacy database reader. */
internal class KnowledgeLibraryRestore(
    private val target: DocumentStorage,
) {
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Bounded preflight precedes individually verified publications.
    suspend fun restore(source: DocumentReadAccess): Int {
        var total = 0L
        val observed = mutableListOf<DocumentStorage.File>()

        fun snapshot(directory: String): List<DocumentStorage.File> =
            source.list(directory).also { files ->
                require(observed.size + files.size <= 1024)
                require(files.distinctBy { it.name }.size == files.size)
                files.forEach {
                    if (directory in setOf("notes", "sources")) require(it.bytes.size <= 4 * 1024 * 1024)
                    total += it.bytes.size
                    require(total <= 64 * 1024 * 1024)
                    observed += it
                }
            }
        val notes =
            snapshot("notes").map { file ->
                val doc = decode(file)
                val note = KnowledgeDocumentCodec.note(doc)
                requireId(note.id)
                require(note.kind in setOf("NOTE", "KNOWLEDGE"))
                Triple(note, KnowledgeDocumentCodec.sources(doc), file)
            }
        val sources =
            snapshot("sources").map { file ->
                KnowledgeDocumentCodec
                    .source(decode(file))
                    .also { requireId(it.id) }
            }
        require(notes.isNotEmpty() || sources.isNotEmpty()) { "LIBRARY_RESTORE_EMPTY" }
        require(notes.distinctBy { it.first.id }.size == notes.size)
        require(sources.distinctBy { it.id }.size == sources.size)
        val originals = sources.associate { it.id to snapshot("originals/${it.id}") }
        // Validate every record and attachment before publishing anything.
        notes.forEach { (note, links, _) ->
            require(links.all { it.noteId == note.id } && links.distinctBy { it.sourceId }.size == links.size)
            unchangedOrAbsent("notes", note.id, KnowledgeDocumentCodec.note(note, links))
        }
        sources.forEach { item ->
            attachmentFiles(item, originals.getValue(item.id))
            val current = existing("sources", item.id)
            if (current != null) {
                val old = KnowledgeDocumentCodec.source(decode(current))
                check(sameSource(old, item)) { "LIBRARY_RESTORE_CONFLICT" }
            }
            originals.getValue(item.id).forEach { original ->
                matchingOriginal("originals/${item.id}", original)
            }
        }
        observed.forEach {
            coroutineContext.ensureActive()
            check(source.read(it.reference, 16 * 1024 * 1024).hash == it.hash) { "LIBRARY_RESTORE_CHANGED" }
        }
        // Publications are individually recoverable. A partial failure is not success; retry only
        // accepts identical already-published content, never overwrites a conflicting destination.
        sources.forEach { item ->
            coroutineContext.ensureActive()
            val attachments = attachmentFiles(item, originals.getValue(item.id))
            val files = Json.parseToJsonElement(item.filesJson) as JsonArray
            val published =
                originals.getValue(item.id).associate { original ->
                    val directory = "originals/${item.id}"
                    val previous = matchingOriginal(directory, original)
                    val index = attachments.indexOf(original)
                    val mime =
                        if (index >= 0) {
                            files[index]
                                .jsonObject
                                .getValue("mime")
                                .jsonPrimitive.content
                        } else if (original.name == "public-page.html") {
                            "text/html"
                        } else {
                            "application/octet-stream"
                        }
                    val saved = previous ?: target.save(directory, original.name, original.bytes, mimeType = mime)
                    original.name to saved
                }
            val remapped =
                JsonArray(
                    files.mapIndexed { index, metadata ->
                        val reference = published.getValue(attachments[index].name).reference
                        JsonObject(metadata.jsonObject + ("reference" to JsonPrimitive(reference)))
                    },
                )
            val restored = normalized(item).copy(filesJson = remapped.toString())
            val current = existing("sources", item.id)
            if (current == null) {
                publish("sources", item.id, KnowledgeDocumentCodec.source(restored))
            } else {
                val old = KnowledgeDocumentCodec.source(decode(current))
                check(
                    sameSource(old, restored) &&
                        Json.parseToJsonElement(old.filesJson) == Json.parseToJsonElement(restored.filesJson),
                ) {
                    "LIBRARY_RESTORE_CONFLICT"
                }
            }
        }
        notes.forEach { (note, links, _) ->
            coroutineContext.ensureActive()
            publish("notes", note.id, KnowledgeDocumentCodec.note(note, links))
        }
        return notes.size + sources.size
    }

    private fun normalized(item: CollectedSource): CollectedSource {
        val files = Json.parseToJsonElement(item.filesJson) as JsonArray
        val interrupted = item.state in setOf("IMPORTING", "EXTRACTING", "QUEUED", "DELETING")
        return item.copy(
            filesJson = JsonArray(files.map { JsonObject(it.jsonObject - "reference") }).toString(),
            state = if (interrupted) "INCOMPLETE" else item.state,
            detail = if (interrupted) "restore-interrupted" else item.detail,
            documentHash = "",
        )
    }

    private fun sameSource(
        first: CollectedSource,
        second: CollectedSource,
    ): Boolean {
        val left = normalized(first)
        val right = normalized(second)
        return left.copy(filesJson = "") == right.copy(filesJson = "") &&
            Json.parseToJsonElement(left.filesJson) == Json.parseToJsonElement(right.filesJson)
    }

    private fun matchingOriginal(
        directory: String,
        original: DocumentStorage.File,
    ): DocumentStorage.File? {
        // Original import slots are 0..7. MediaStore can replace/append MIME extensions;
        // verify the slot and bytes, not an assumed unchanged display filename.
        var bytes = 0L
        val entries =
            target.references(directory).also { require(it.size <= 1024) }.map { reference ->
                target.read(reference, 16 * 1024 * 1024).also {
                    bytes += it.bytes.size
                    require(bytes <= 64 * 1024 * 1024)
                }
            }
        val slot = original.name.substringBefore('.')
        val indexed = slot in (0..7).map(Int::toString)
        val matches =
            entries.filter {
                if (indexed) {
                    it.name.substringBefore('.') == slot
                } else {
                    it.name == original.name || it.name.startsWith(original.name + ".")
                }
            }
        check(matches.size <= 1 && matches.all { it.hash == original.hash }) { "LIBRARY_RESTORE_CONFLICT" }
        return matches.singleOrNull()
    }

    private fun attachmentFiles(
        item: CollectedSource,
        originals: List<DocumentStorage.File>,
    ): List<DocumentStorage.File> {
        val files = Json.parseToJsonElement(item.filesJson) as JsonArray
        require(files.size <= 8)
        return files.mapIndexed { index, metadata ->
            val data = metadata.jsonObject
            require(
                data
                    .getValue("name")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
            val mime = data.getValue("mime").jsonPrimitive.content
            require(mime.length <= 200 && mime.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+")))
            require(
                data
                    .getValue("reference")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
            requireNotNull(originals.singleOrNull { it.name.startsWith("$index.") }) {
                "LIBRARY_RESTORE_ATTACHMENT_MISSING"
            }
        }
    }

    private fun requireId(id: String) = require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))

    private fun decode(file: DocumentStorage.File) =
        KnowledgeDocumentCodec.decode(
            Charsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(file.bytes))
                .toString(),
        )

    private fun existing(
        directory: String,
        id: String,
    ): DocumentStorage.File? {
        val matches =
            target.list(directory).filter {
                decode(it)
                    .metadata
                    .getValue("id")
                    .jsonPrimitive.content == id
            }
        check(matches.size <= 1) { "LIBRARY_RESTORE_CONFLICT" }
        return matches.singleOrNull()
    }

    private fun unchangedOrAbsent(
        directory: String,
        id: String,
        text: String,
    ): DocumentStorage.File? =
        existing(directory, id).also {
            check(it == null || it.bytes.contentEquals(text.toByteArray())) { "LIBRARY_RESTORE_CONFLICT" }
        }

    private fun publish(
        directory: String,
        id: String,
        text: String,
    ) {
        if (unchangedOrAbsent(directory, id, text) == null) {
            val bytes = text.toByteArray()
            require(bytes.size <= 4 * 1024 * 1024)
            val current = target.list(directory)
            require(current.size < 4096 && current.sumOf { it.bytes.size.toLong() } + bytes.size <= 32 * 1024 * 1024)
            target.save(directory, "$id.md", bytes)
        }
    }
}
