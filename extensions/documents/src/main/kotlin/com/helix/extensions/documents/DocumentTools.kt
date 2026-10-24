package com.helix.extensions.documents

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.PublishedToolImage
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.VisualPreparationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds

object DocumentTools {
    val origin = ToolOrigin.PluginOrigin("documents", "1", "documents")
    val names = setOf("documents.inspect", "documents.read", "documents.view")

    fun owns(descriptor: ToolDescriptor): Boolean = descriptor.origin == origin && descriptor.name.value in names

    fun bindings(
        snapshots: DocumentSnapshots,
        parser: DocumentParser,
        images: ToolImagePublication,
    ): List<ToolBinding> =
        listOf("inspect", "read", "view").map { operation ->
            ToolBinding(
                descriptor(operation),
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                        try {
                            perform(call, operation, snapshots, parser, images)
                        } catch (failure: DocumentException) {
                            failureResult(failure.code)
                        } catch (failure: VisualPreparationException) {
                            failureResult(failure.code)
                        } catch (_: java.io.IOException) {
                            ToolExecutorResult.Failed("Document source or image unavailable", sideEffectFree = true)
                        } catch (_: IllegalArgumentException) {
                            ToolExecutorResult.Failed("Invalid document reference or range", sideEffectFree = true)
                        }
                },
            )
        }

    private fun failureResult(code: String): ToolExecutorResult =
        when (code) {
            "CANCELLED" -> {
                ToolExecutorResult.Cancelled
            }

            "TIMED_OUT" -> {
                ToolExecutorResult.TimedOut
            }

            else -> {
                ToolExecutorResult.Failed(
                    "Document unavailable: $code. " + failureHelp(code),
                    sideEffectFree = true,
                )
            }
        }

    private fun failureHelp(code: String): String =
        when (code) {
            "REF_EXPIRED" -> "Inspect the original source again under current authorization."
            "SCOPE_DENIED" -> "Ask the user to restore access to the original source."
            "UNSUPPORTED_FORMAT" -> "Provide a PDF or DOCX; DOC and DOCM are unsupported."
            "ENCRYPTED_DOCUMENT" -> "Provide an unlocked document copy."
            "RESOURCE_LIMIT", "IMAGE_LIMIT" -> "Split the document or use smaller images."
            "UNSUPPORTED_OBJECT" -> "Provide a PDF export; external and embedded executable objects are not rendered."
            "RANGE_OUT_OF_BOUNDS" -> "Use a page/block/image location reported by inspect or read."
            else -> "Check source validity and parser availability before a bounded retry; do not assume empty content."
        }

    private fun perform(
        call: ExecutableToolCall,
        operation: String,
        snapshots: DocumentSnapshots,
        parser: DocumentParser,
        images: ToolImagePublication,
    ): ToolExecutorResult.Completed {
        DocumentLimits.checkActive(call)
        val snapshot =
            if (operation ==
                "inspect"
            ) {
                snapshots.create(
                    call,
                    call.args
                        .getValue("path")
                        .jsonPrimitive.content,
                )
            } else {
                snapshots.resolve(
                    call,
                    call.args
                        .getValue("documentRef")
                        .jsonPrimitive.content,
                )
            }
        val result = parser.parse(call, snapshot, operation)
        DocumentLimits.checkActive(call)
        require(snapshot.sha256.matches(Regex("[a-f0-9]{64}")))
        require(
            result.image == null ||
                (operation == "view" && result.image.size in 1..DocumentLimits.IMAGE_BYTES),
        )
        val published = result.image?.let { images.publish(call, it, null) }
        DocumentLimits.checkActive(call)
        val output =
            buildJsonObject {
                result.metadata.forEach { (key, value) -> put(key, value) }
                put("documentRef", snapshot.reference)
                put("sourcePath", snapshot.sourcePath)
                put("sourceSha256", snapshot.sha256)
                put("format", snapshot.format)
                if (operation == "view") {
                    put("imagePrepared", published?.visual != null)
                    put(
                        "status",
                        if (published?.visual !=
                            null
                        ) {
                            "IMAGE_PREPARED"
                        } else {
                            "VISUAL_UNAVAILABLE"
                        },
                    )
                    published?.let {
                        put("imagePath", it.reference)
                        put("note", it.note)
                    }
                }
            }
        return ToolExecutorResult.Completed(output, visualArtifact = published?.visual)
    }

    fun descriptor(operation: String): ToolDescriptor =
        ToolDescriptor(
            name = ToolName("documents.$operation"),
            version = ToolVersion(1),
            description =
                when (operation) {
                    "inspect" -> (
                        "Inspect an authorized PDF or DOCX (maximum 20 MiB) and create an immutable " +
                            "session-bound documentRef. Reports capabilities and counts, not document " +
                            "prose. Select this plugin explicitly; refresh with inspect when the source " +
                            "changes."
                    )

                    "read" -> (
                        "Read one PDF page (1-based page) or up to 32 DOCX body blocks (startBlock, " +
                            "1-based). Follow nextCursor as startBlock. Cite sourceSha256 and page/block " +
                            "locations; truncated or unread content is not full coverage. Use " +
                            "documents.view for diagrams, scanned pages and embedded images."
                    )

                    "view" -> (
                        "Observe one PDF page, optionally region [left,top,right,bottom] normalized " +
                            "0..1, or one DOCX imageId returned by read. Uses actual model vision; " +
                            "unavailable pixels are not viewed. Does not fetch external images, OCR, " +
                            "edit or upload the original document."
                    )

                    else -> error("Unknown document operation")
                },
            inputSchema = inputSchema(operation),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 20.seconds,
            maxOutputBytes = 350_000,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
            discoveryCompanions = names.filter { it != "documents.$operation" }.map(::ToolName).toSet(),
        )

    private fun outputSchema() =
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                listOf(
                    "status",
                    "documentRef",
                    "sourcePath",
                    "sourceSha256",
                    "format",
                    "coverage",
                    "text",
                    "imageId",
                    "imagePath",
                    "note",
                ).forEach { key ->
                    putJsonObject(key) { put("type", "string") }
                }
                listOf("pageCount", "page", "nextPage", "blockCount", "imageCount", "nextCursor").forEach { key ->
                    putJsonObject(key) {
                        put("type", "integer")
                        put("minimum", 0)
                    }
                }
                listOf("truncated", "partial", "textExtractionSupported", "imagePrepared", "eof").forEach { key ->
                    putJsonObject(key) { put("type", "boolean") }
                }
                putJsonObject("warnings") {
                    put("type", "array")
                    put("maxItems", 128)
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("blocks") {
                    put("type", "array")
                    put("maxItems", 32)
                    putJsonObject("items") { put("type", "object") }
                }
                putJsonObject("region") {
                    put("type", "array")
                    put("minItems", 4)
                    put("maxItems", 4)
                    putJsonObject("items") { put("type", "number") }
                }
            }
            putJsonArray("required") { listOf("documentRef", "sourcePath", "sourceSha256", "format").forEach(::add) }
        }

    private fun inputSchema(operation: String) =
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                val key = if (operation == "inspect") "path" else "documentRef"
                putJsonObject(key) {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 4096)
                }
                if (operation !=
                    "inspect"
                ) {
                    putJsonObject("page") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", Int.MAX_VALUE)
                    }
                }
                if (operation ==
                    "read"
                ) {
                    putJsonObject("startBlock") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", Int.MAX_VALUE)
                    }
                }
                if (operation == "view") {
                    putJsonObject("imageId") {
                        put("type", "string")
                        put("maxLength", 128)
                    }
                    putJsonObject("region") {
                        put("type", "array")
                        put("minItems", 4)
                        put("maxItems", 4)
                        putJsonObject("items") {
                            put("type", "number")
                            put("minimum", 0)
                            put("maximum", 1)
                        }
                    }
                }
            }
            putJsonArray("required") { add(if (operation == "inspect") "path" else "documentRef") }
        }
}
