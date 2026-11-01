package com.helix.app.knowledge

import com.helix.core.storage.knowledge.CollectedSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class SemanticDocument(
    val key: String,
    val revision: String,
    val title: String,
    val text: String,
) {
    override fun hashCode() = 31 * key.hashCode() + revision.hashCode()
}

internal data class SemanticChunk(
    val start: Int,
    val end: Int,
    val vector: FloatArray,
)

internal data class SemanticHit(
    val document: SemanticDocument,
    val start: Int,
    val end: Int,
)

/** PDF page delimiters are locations, not extracted scan text. Keep original content untouched. */
internal fun semanticSourceDocument(source: CollectedSource): SemanticDocument? {
    val text = source.extracted.ifBlank { source.rawText }
    val pdfCoverage = source.extraction.contains("\"pages\"")
    val material =
        if (source.rawText.isBlank() && pdfCoverage) {
            text.replace(Regex("\\n\\n## PDF page [0-9]+\\n"), "").trim()
        } else {
            text
        }
    if (material.isBlank()) return null
    return SemanticDocument("source:${source.id}", knowledgeSourceRevision(source), source.title, text)
}

/** Derived cache only. A hit is usable only against the same currently readable document revision. */
internal class SemanticIndex(
    private val directory: File,
) {
    fun read(
        namespace: String,
        document: SemanticDocument,
    ): List<SemanticChunk>? {
        val file = path(namespace, document.key)
        if (!file.isFile || file.length() > MAX_FILE) return null
        return try {
            val bytes = file.readBytes()
            require(bytes.size >= 64)
            val payload = bytes.copyOfRange(64, bytes.size)
            require(String(bytes, 0, 64, Charsets.US_ASCII) == knowledgeHashBytes(payload))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readUTF() == "HELIX_SEMANTIC_1")
                require(
                    input.readUTF() == namespace && input.readUTF() == document.key &&
                        input.readUTF() == document.revision,
                )
                val dimension = input.readInt().also { require(it in 1..8192) }
                val count = input.readInt().also { require(it in 1..256) }
                val total = document.text.codePointCount(0, document.text.length)
                val chunks =
                    List(count) {
                        val start = input.readInt()
                        val end = input.readInt()
                        require(start in 0 until end && end <= total)
                        val vector =
                            FloatArray(dimension) {
                                val value = input.readFloat()
                                require(value.isFinite())
                                value
                            }
                        require(vector.sumOf { it.toDouble() * it } in 0.99..1.01)
                        SemanticChunk(start, end, vector)
                    }
                require(input.available() == 0)
                chunks
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @Synchronized fun write(
        namespace: String,
        document: SemanticDocument,
        chunks: List<SemanticChunk>,
    ) {
        require(chunks.isNotEmpty() && chunks.size <= 256)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF("HELIX_SEMANTIC_1")
            output.writeUTF(namespace)
            output.writeUTF(document.key)
            output.writeUTF(document.revision)
            output.writeInt(chunks.first().vector.size)
            output.writeInt(chunks.size)
            chunks.forEach { chunk ->
                require(chunk.vector.size == chunks.first().vector.size)
                output.writeInt(chunk.start)
                output.writeInt(chunk.end)
                chunk.vector.forEach(output::writeFloat)
            }
        }
        val payload = bytes.toByteArray()
        require(payload.size + 64 <= MAX_FILE)
        directory.mkdirs()
        val target = path(namespace, document.key)
        val used =
            directory
                .listFiles()
                .orEmpty()
                .filter { it != target }
                .sumOf(File::length)
        require(used + payload.size + 64 <= 32L * 1024 * 1024) { "Semantic cache capacity reached" }
        val temp = Files.createTempFile(directory.toPath(), "index-", ".tmp")
        try {
            Files.write(temp, knowledgeHashBytes(payload).toByteArray(Charsets.US_ASCII) + payload)
            Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Synchronized fun retain(
        namespace: String,
        keys: Set<String>,
    ) {
        val keep = keys.map { path(namespace, it).name }.toSet()
        directory
            .listFiles()
            .orEmpty()
            .filter {
                it.name !in keep &&
                    it.name.matches(
                        Regex("([a-f0-9]{64}\\.idx|index-[A-Za-z0-9_-]+\\.tmp)"),
                    )
            }.forEach {
                check(it.delete()) { "Semantic cache removal failed" }
            }
    }

    private fun path(
        namespace: String,
        key: String,
    ) = File(directory, knowledgeHash("$namespace|$key") + ".idx")

    companion object {
        private const val MAX_FILE = 8L * 1024 * 1024

        fun ranges(text: String): List<Pair<Int, Int>> {
            val total = text.codePointCount(0, text.length)
            require(total in 1..200_000)
            val ranges = mutableListOf<Pair<Int, Int>>()
            var start = 0
            while (start < total) {
                val end = minOf(total, start + 1024)
                ranges += start to end
                if (end == total) break
                start = end - 96
            }
            return ranges
        }

        fun text(
            document: SemanticDocument,
            start: Int,
            end: Int,
        ): String =
            document.text.substring(
                document.text.offsetByCodePoints(0, start),
                document.text.offsetByCodePoints(0, end),
            )

        /** RRF combines ranks rather than pretending lexical counts and cosine scores share a scale. */
        fun rank(
            query: String,
            vector: FloatArray?,
            documents: List<Pair<SemanticDocument, List<SemanticChunk>>>,
        ): List<SemanticHit> {
            val terms = KnowledgeRetrieval.terms(query)
            val rows =
                documents.flatMap { (doc, chunks) ->
                    chunks.map { Triple(SemanticHit(doc, it.start, it.end), it.vector, text(doc, it.start, it.end)) }
                }
            val lexical =
                rows
                    .map { row ->
                        row.first to terms.count { row.third.contains(it, true) }
                    }.filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .take(32)
                    .map { it.first }
            val semantic =
                if (vector == null) {
                    emptyList()
                } else {
                    rows
                        .mapNotNull { row ->
                            if (row.second.size !=
                                vector.size
                            ) {
                                null
                            } else {
                                row.first to
                                    row.second.indices.sumOf { row.second[it].toDouble() * vector[it] }
                            }
                        }.filter { it.second > 0 }
                        .sortedByDescending { it.second }
                        .take(32)
                        .map { it.first }
                }
            val scores = linkedMapOf<SemanticHit, Double>()
            listOf(lexical, semantic).forEach { list ->
                list.forEachIndexed { rank, hit ->
                    scores[hit] =
                        scores.getOrDefault(hit, 0.0) + 1.0 / (61 + rank)
                }
            }
            return scores.entries
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key }
        }
    }
}

private fun knowledgeHashBytes(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
