package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.FileScopePath
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.VisualPreparationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

object ReadPdfTool {
    const val NAME = "files.read_pdf"

    fun descriptor() =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(1),
            description =
                "Read ONE page of an authorized PDF for analysis or summarization. Page numbers start at 1. " +
                    "auto returns text when available, otherwise a page image requiring a vision-capable model. " +
                    "Use image for diagrams, tables or layout. Follow nextPage to read more. " +
                    "Summarize only pages actually read; cite pages and disclose truncation/unread pages. " +
                    "A changed source hash means a different document snapshot. " +
                    "Do not read PDF binary/base64 as prose. Maximum source 20 MiB. " +
                    "No editing, OCR or whole-PDF upload.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 30.seconds,
            maxOutputBytes = 150_000,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun inputSchema() =
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 4096)
                }
                putJsonObject("page") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", Int.MAX_VALUE)
                }
                putJsonObject("mode") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add("auto")
                        add("image")
                    }
                }
            }
            putJsonArray("required") { add("path") }
        }

    private fun outputSchema() =
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                listOf("status", "sourceSha256", "text", "note", "imagePath").forEach { key ->
                    putJsonObject(key) { put("type", "string") }
                }
                listOf("page", "pageCount", "nextPage").forEach { key ->
                    putJsonObject(key) {
                        put("type", "integer")
                        put("minimum", 1)
                    }
                }
                listOf("textTruncated", "eof", "textExtractionSupported", "imageDelivered").forEach { key ->
                    putJsonObject(key) { put("type", "boolean") }
                }
            }
            putJsonArray("required") {
                listOf(
                    "status",
                    "sourceSha256",
                    "text",
                    "page",
                    "pageCount",
                    "textTruncated",
                    "eof",
                    "textExtractionSupported",
                    "imageDelivered",
                    "note",
                ).forEach(::add)
            }
        }

    fun executor(
        reader: PdfPageReader,
        images: ToolImagePublication,
    ): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                try {
                    readPage(call, reader, images)
                } catch (failure: PdfReadException) {
                    when (failure.code) {
                        "CANCELLED" -> ToolExecutorResult.Cancelled
                        "TIMED_OUT" -> ToolExecutorResult.TimedOut
                        else -> ToolExecutorResult.Failed("PDF unavailable: ${failure.code}", sideEffectFree = true)
                    }
                } catch (failure: VisualPreparationException) {
                    when (failure.code) {
                        "CANCELLED" -> ToolExecutorResult.Cancelled
                        "TIMED_OUT" -> ToolExecutorResult.TimedOut
                        else -> ToolExecutorResult.Failed("PDF pixels unavailable: ${failure.code}")
                    }
                } catch (_: IOException) {
                    ToolExecutorResult.Failed("PDF image publication failed")
                } catch (_: IllegalArgumentException) {
                    ToolExecutorResult.Failed(
                        "PDF requires a valid workspace path, page and bounded result",
                        sideEffectFree = true,
                    )
                }
        }

    private fun readPage(
        call: ExecutableToolCall,
        reader: PdfPageReader,
        images: ToolImagePublication,
    ): ToolExecutorResult.Completed {
        PdfSourceSnapshot.checkActive(call)
        val path =
            requireNotNull(
                call.args["path"]
                    ?.jsonPrimitive
                    ?.takeIf { it.isString }
                    ?.content,
            )
        FileScopePath.fromModelReference(path)
        val page = call.args["page"]?.let { requireNotNull(it.jsonPrimitive.intOrNull) } ?: 1
        require(page > 0)
        val mode = call.args["mode"]?.jsonPrimitive?.content ?: "auto"
        require(mode == "auto" || mode == "image")
        val result = reader.read(call, path, page, mode == "image")
        PdfSourceSnapshot.checkActive(call)
        require(result.pageCount >= page && result.sourceSha256.matches(Regex("[a-f0-9]{64}")))
        require(result.text.length <= PdfSourceSnapshot.MAX_TEXT)
        val png = result.png
        require(png == null || png.size in 1..PdfSourceSnapshot.MAX_PNG_BYTES)
        val published = png?.let { images.publish(call, it, null) }
        PdfSourceSnapshot.checkActive(call)
        return ToolExecutorResult.Completed(
            buildJsonObject {
                val unavailable = result.text.isBlank() && published?.visual == null
                put("status", if (unavailable) "CONTENT_UNAVAILABLE" else "PAGE_READ")
                put("sourceSha256", result.sourceSha256)
                put("page", page)
                put("pageCount", result.pageCount)
                if (page < result.pageCount) put("nextPage", page + 1)
                put("eof", page == result.pageCount)
                put("text", result.text)
                put("textTruncated", result.textTruncated)
                put("textExtractionSupported", result.textExtractionSupported)
                put("imageDelivered", published?.visual != null)
                published?.let { put("imagePath", it.reference) }
                put("note", published?.note ?: "Text may omit diagrams; use image for visual content.")
            },
            visualArtifact = published?.visual,
        )
    }

    fun register(
        registry: ToolRegistry,
        reader: PdfPageReader,
        images: ToolImagePublication,
    ) {
        registry.register(descriptor(), executor(reader, images))
    }
}
