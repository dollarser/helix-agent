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
import org.json.JSONArray
import java.io.File

internal class KnowledgeExtractor(
    private val root: File,
    private val store: KnowledgeStore,
    private val fetcher: HttpFetchBridge,
) {
    @Suppress("TooGenericExceptionCaught") // Failed extraction is durable, never a fabricated success.
    suspend fun extract(original: CollectedSource) {
        store.save(original.copy(state = "EXTRACTING"))
        val sections = mutableListOf<String>()
        val notes = mutableListOf<String>()
        if (original.rawText.isNotBlank()) sections += original.rawText
        try {
            extractFiles(original, sections, notes)
            if (original.sourceUrl.isNotBlank()) extractLink(original, sections, notes)
            val combined = sections.joinToString("\n\n")
            if (combined.length > MAX_KNOWLEDGE_TEXT) notes += "truncated"
            store.save(
                original.copy(
                    state = if (notes.isEmpty()) "READY" else "INCOMPLETE",
                    extracted = combined.take(MAX_KNOWLEDGE_TEXT),
                    detail = notes.distinct().joinToString(","),
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            store.save(original.copy(state = "FAILED", detail = "extraction-failed"))
        }
    }

    private fun extractFiles(
        item: CollectedSource,
        sections: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val files = JSONArray(item.filesJson)
        for (index in 0 until files.length()) {
            val mime = files.getJSONObject(index).getString("mime")
            val bytes = knowledgeOriginal(root, item, index).readBytes()
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
            File(File(root, item.id), "public-page.html").writeText(response.body)
            val result = DocumentTextExtractor.extract(TextAttachmentKind.HTML, response.body.toByteArray())
            appendResult(result, sections, notes)
            notes += "public-page-unverified"
            if (response.truncated || response.body.length < 200) notes += "limited-page"
        } else {
            notes += "link-unavailable"
        }
    }
}
