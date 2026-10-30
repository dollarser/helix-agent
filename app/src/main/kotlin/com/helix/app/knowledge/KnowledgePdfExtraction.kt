package com.helix.app.knowledge

import android.content.Context
import android.os.Build
import com.helix.app.documents.AndroidDocumentParser
import com.helix.app.documents.DocumentParseWork
import com.helix.core.model.TextAttachmentKind
import com.helix.extensions.documents.DocumentException
import com.helix.feature.files.DocumentTextExtractor
import com.helix.feature.files.ExtractedText
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Instant

/** User-imported bytes only; shares the existing isolated native parser, without issuing a tool call. */
internal class KnowledgePdfExtraction(
    private val context: Context,
) {
    private val parser = AndroidDocumentParser(context) { _, _ -> error("Import never resolves model paths") }

    data class Result(
        val text: String,
        val coverage: JsonObject,
        val incomplete: Boolean,
    )

    fun readPage(
        bytes: ByteArray,
        page: Int,
        work: DocumentParseWork,
        offset: Int,
        limit: Int,
    ): JsonObject {
        val file = Files.createTempFile(context.cacheDir.toPath(), "knowledge-page-", ".pdf").toFile()
        try {
            file.writeBytes(bytes)
            return parser.importedPdfPage(file, page, false, work, offset, limit)
        } finally {
            file.delete()
        }
    }

    suspend fun extract(bytes: ByteArray): Result {
        if (Build.VERSION.SDK_INT < 35) return limited(bytes)
        val job = currentCoroutineContext()[Job]
        val work = DocumentParseWork(Instant.now().plusSeconds(20)) { job?.isActive == false }
        val file = Files.createTempFile(context.cacheDir.toPath(), "knowledge-pdf-", ".pdf").toFile()
        try {
            file.writeBytes(bytes)
            val inspection = parser.importedPdfPage(file, 1, true, work)
            val total = inspection.getValue("pageCount").jsonPrimitive.int
            val collector = PdfImportText()
            var page = 1
            var failure: String? = null
            while (page <= total && collector.hasCapacity && failure == null) {
                currentCoroutineContext().ensureActive()
                val result =
                    try {
                        parser.importedPdfPage(file, page, false, work)
                    } catch (error: DocumentException) {
                        currentCoroutineContext().ensureActive()
                        failure = error.code
                        null
                    }
                result?.let { collector.append(page, it) }
                page++
            }
            return collector.result(total, failure)
        } finally {
            file.delete()
        }
    }

    private fun limited(bytes: ByteArray): Result {
        val extracted = DocumentTextExtractor.extract(TextAttachmentKind.PDF, bytes)
        val text =
            when (extracted) {
                is ExtractedText.Success -> extracted.text
                is ExtractedText.Truncated -> extracted.text
                ExtractedText.Unreadable -> ""
            }
        return Result(
            text,
            buildJsonObject {
                put("reason", "platform_text_unavailable")
                put("processedPages", 0)
                put("textOnly", true)
                put("truncated", true)
            },
            true,
        )
    }
}
