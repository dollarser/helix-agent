package com.helix.extensions.documents

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.VisualArtifact
import com.helix.core.workspace.FileScopePath
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.NoCancellation
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DocumentToolsTest {
    private val hash = "a".repeat(64)
    private val snapshot = DocumentSnapshot("ref", "scope:ws:paper.pdf", hash, "pdf")

    private fun call(
        operation: String,
        args: String = """{"documentRef":"ref","page":2}""",
    ) = ExecutableToolCall(
        "c",
        "documents.$operation",
        "1",
        Json.parseToJsonElement(args).jsonObject,
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.now().plusSeconds(30),
        NoCancellation,
        "s",
        "t",
    )

    private val snapshots =
        object : DocumentSnapshots {
            override fun create(
                call: ExecutableToolCall,
                path: String,
            ): DocumentSnapshot {
                FileScopePath.fromModelReference(path)
                return snapshot
            }

            override fun resolve(
                call: ExecutableToolCall,
                reference: String,
            ): DocumentSnapshot {
                if (reference != "ref" ||
                    call.sessionId != "s"
                ) {
                    throw DocumentException("REF_EXPIRED")
                }
                return snapshot
            }
        }
    private val noImage = ToolImagePublication { _, _, _ -> error("Text must not publish pixels") }

    private fun executor(
        operation: String,
        parser: DocumentParser,
        images: ToolImagePublication = noImage,
    ) = DocumentTools
        .bindings(snapshots, parser, images)
        .single {
            it.descriptor.name.value ==
                "documents.$operation"
        }.executor

    @Test fun textPaginationPreservesSnapshotSourceAndReadOnlyOrigin() {
        val parser =
            DocumentParser { _, bound, operation ->
                assertEquals(snapshot, bound)
                assertEquals("read", operation)
                DocumentResult(
                    buildJsonObject {
                        put("text", "actual page")
                        put("page", 2)
                        put("nextPage", 3)
                        put("truncated", true)
                    },
                )
            }
        val output = (executor("read", parser).execute(call("read")) as ToolExecutorResult.Completed).output.jsonObject
        assertEquals(hash, output["sourceSha256"]!!.jsonPrimitive.content)
        assertEquals("scope:ws:paper.pdf", output["sourcePath"]!!.jsonPrimitive.content)
        assertEquals("3", output["nextPage"]!!.jsonPrimitive.content)
        assertEquals("true", output["truncated"]!!.jsonPrimitive.content)
        DocumentTools.names.forEach { name ->
            assertEquals(
                ToolOperationClass.READ_ONLY,
                DocumentTools.descriptor(name.substringAfter('.')).operationClass,
            )
        }
    }

    @Test fun visualPublicationKeepsOriginalCallWithoutScreenAuthorization() {
        val visual = VisualArtifact("id", hash, "image/png", 3, 1, 1)
        val result =
            executor(
                "view",
                DocumentParser { _, _, _ -> DocumentResult(JsonObject(emptyMap()), byteArrayOf(1, 2, 3)) },
                ToolImagePublication { bound, bytes, scope ->
                    assertEquals("t", bound.turnId)
                    assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                    assertNull(scope)
                    PublishedToolImage("scope:ws:output/page.png", hash, 3, visual)
                },
            ).execute(call("view")) as ToolExecutorResult.Completed
        assertEquals(visual, result.visualArtifact)
        assertEquals(
            "true",
            result.output.jsonObject["imagePrepared"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun unavailableVisionNeverClaimsPixelsWereDelivered() {
        val result =
            executor(
                "view",
                DocumentParser {
                    _,
                    _,
                    _,
                    ->
                    DocumentResult(JsonObject(emptyMap()), byteArrayOf(1))
                },
                ToolImagePublication {
                    _,
                    _,
                    _,
                    ->
                    PublishedToolImage("scope:ws:image.png", hash, 1, null, "VISION_UNAVAILABLE")
                },
            ).execute(call("view")) as ToolExecutorResult.Completed
        assertNull(result.visualArtifact)
        assertEquals(
            "VISUAL_UNAVAILABLE",
            result.output.jsonObject["status"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "false",
            result.output.jsonObject["imagePrepared"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun invalidPathsCancellationAndTimeoutNeverEnterParser() {
        val parser = DocumentParser { _, _, _ -> error("Must not parse") }
        val execute = executor("inspect", parser)
        for (path in listOf(
            "/sdcard/secret.pdf",
            "scope:ws:../secret.pdf",
        )) {
            assertTrue(execute.execute(call("inspect", """{"path":"$path"}""")) is ToolExecutorResult.Failed)
        }
        assertEquals(ToolExecutorResult.TimedOut, execute.execute(call("inspect").copy(deadline = Instant.EPOCH)))
        assertEquals(
            ToolExecutorResult.Cancelled,
            execute.execute(
                call("inspect").copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = true
                        },
                ),
            ),
        )
    }

    @Test fun crossSessionOrExpiredReferencesFailBeforeParsing() {
        val execute = executor("read", DocumentParser { _, _, _ -> error("Must not parse") })
        assertTrue(execute.execute(call("read").copy(sessionId = "other")) is ToolExecutorResult.Failed)
        assertTrue(execute.execute(call("read", """{"documentRef":"missing"}""")) is ToolExecutorResult.Failed)
    }

    @Test fun parserFailuresAndOversizedImagesAreNotSuccessfulReadings() {
        assertTrue(
            executor(
                "view",
                DocumentParser { _, _, _ ->
                    DocumentResult(
                        JsonObject(emptyMap()),
                        ByteArray(DocumentLimits.IMAGE_BYTES + 1),
                    )
                },
            ).execute(call("view")) is ToolExecutorResult.Failed,
        )
        for (reason in listOf(
            "MALFORMED_DOCUMENT",
            "ENCRYPTED_DOCUMENT",
            "RANGE_OUT_OF_BOUNDS",
            "PARSER_DIED",
            "RESOURCE_LIMIT",
            "SCOPE_DENIED",
        )) {
            val result =
                executor(
                    "read",
                    DocumentParser {
                        _,
                        _,
                        _,
                        ->
                        throw DocumentException(reason)
                    },
                ).execute(call("read"))
            assertTrue(result is ToolExecutorResult.Failed)
            assertTrue(result.toString().contains(reason))
        }
    }

    @Test fun cancellationDuringPublicationDoesNotReportCompleted() {
        var cancelled = false
        val result =
            executor(
                "view",
                DocumentParser { _, _, _ -> DocumentResult(JsonObject(emptyMap()), byteArrayOf(1)) },
                ToolImagePublication { _, _, _ ->
                    cancelled = true
                    PublishedToolImage("scope:ws:image.png", hash, 1, null)
                },
            ).execute(
                call("view").copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = cancelled
                        },
                ),
            )
        assertEquals(ToolExecutorResult.Cancelled, result)
    }
}
