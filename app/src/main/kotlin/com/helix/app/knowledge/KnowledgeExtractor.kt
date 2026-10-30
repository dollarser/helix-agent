package com.helix.app.knowledge

import com.helix.core.model.TextAttachmentKind
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.feature.files.DocumentTextExtractor
import com.helix.feature.files.ExtractedText
import com.helix.tools.android.HttpFetchBridge
import com.helix.tools.android.HttpFetchRequest
import com.helix.tools.android.HttpFetchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

internal class KnowledgeExtractor(
    private val store: KnowledgeStore,
    private val fetcher: HttpFetchBridge,
    private val pdf: KnowledgePdfExtraction,
) {
    @Suppress("TooGenericExceptionCaught") // Failed extraction is durable, never a fabricated success.
    suspend fun extract(original: CollectedSource) {
        val working = store.save(original.copy(state = "EXTRACTING"))
        val sections = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val reports = mutableListOf<kotlinx.serialization.json.JsonObject>()
        if (original.rawText.isNotBlank()) sections += original.rawText
        try {
            val files = extractFiles(original, sections, notes, reports)
            if (original.sourceUrl.isNotBlank()) extractLink(original, sections, notes)
            val combined = sections.joinToString("\n\n")
            if (combined.length > MAX_KNOWLEDGE_TEXT) notes += "truncated"
            store.save(
                working.copy(
                    state = if (notes.isEmpty()) "READY" else "INCOMPLETE",
                    extracted = knowledgeTextPrefix(combined, MAX_KNOWLEDGE_TEXT),
                    filesJson = files,
                    detail = notes.distinct().joinToString(","),
                    extraction =
                        kotlinx.serialization.json
                            .JsonArray(reports)
                            .toString(),
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: com.helix.extensions.documents.DocumentException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            store.save(working.copy(state = "FAILED", detail = "pdf-${failure.code}"))
        } catch (_: Exception) {
            store.save(working.copy(state = "FAILED", detail = "extraction-failed"))
        }
    }

    private suspend fun extractFiles(
        item: CollectedSource,
        sections: MutableList<String>,
        notes: MutableList<String>,
        reports: MutableList<kotlinx.serialization.json.JsonObject>,
    ): String {
        val files = JSONArray(item.filesJson)
        for (index in 0 until files.length()) {
            val mime = files.getJSONObject(index).getString("mime")
            val bytes = knowledgeOriginal(store, item, index).bytes
            files
                .getJSONObject(index)
                .put(
                    "sha256",
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(bytes)
                        .joinToString("") { "%02x".format(it) },
                ).put("size", bytes.size)
            if (mime == "application/pdf") {
                val result = pdf.extract(bytes)
                val prefix = sections.sumOf { it.length } + sections.size * 2
                sections += result.text
                reports +=
                    kotlinx.serialization.json.JsonObject(
                        result.coverage +
                            mapOf(
                                "fileIndex" to kotlinx.serialization.json.JsonPrimitive(index),
                                "textStart" to kotlinx.serialization.json.JsonPrimitive(prefix),
                            ),
                    )
                if (result.incomplete) notes += "pdf-text-only"
                if (result.text.isBlank()) notes += "no-readable-text"
                continue
            }
            val kind =
                when (mime) {
                    "text/html" -> TextAttachmentKind.HTML
                    "application/pdf" -> TextAttachmentKind.PDF
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> TextAttachmentKind.DOCX
                    else -> null
                }
            val result =
                when {
                    kind != null -> {
                        DocumentTextExtractor.extract(kind, bytes)
                    }

                    mime.startsWith("text/") -> {
                        val text = bytes.toString(Charsets.UTF_8)
                        if (text.length > MAX_KNOWLEDGE_TEXT) {
                            ExtractedText.Truncated(text.take(MAX_KNOWLEDGE_TEXT))
                        } else {
                            ExtractedText.Success(text)
                        }
                    }

                    else -> {
                        ExtractedText.Unreadable
                    }
                }
            appendResult(result, sections, notes)
        }
        return files.toString()
    }

    private fun appendResult(
        result: ExtractedText,
        sections: MutableList<String>,
        notes: MutableList<String>,
    ) {
        when (result) {
            is ExtractedText.Success -> {
                if (result.text.isNotBlank()) sections += result.text else notes += "no-readable-text"
            }

            is ExtractedText.Truncated -> {
                sections += result.text
                notes += "truncated"
            }

            ExtractedText.Unreadable -> {
                notes += "original-only"
            }
        }
    }

    private fun extractLink(
        item: CollectedSource,
        sections: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val response =
            fetcher.fetch(
                HttpFetchRequest(
                    item.sourceUrl,
                    "GET",
                    1024 * 1024,
                    System.currentTimeMillis() + 20_000,
                ),
            )
        if (response.status == HttpFetchStatus.FETCHED && response.httpStatus in 200..299 &&
            response.contentType.contains("html")
        ) {
            store.documents.save(
                "originals/${item.id}",
                "public-page.html",
                response.body.toByteArray(),
                mimeType = "text/html",
            )
            val result = DocumentTextExtractor.extract(TextAttachmentKind.HTML, response.body.toByteArray())
            appendResult(result, sections, notes)
            notes += "public-page-unverified"
            if (response.truncated || response.body.length < 200) notes += "limited-page"
        } else {
            notes += "link-unavailable"
        }
    }
}
