package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.VisualArtifact
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.PublishedToolImage
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImagePublication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReadPdfToolTest {
    private val hash = "a".repeat(64)

    private fun call(args: String = """{"path":"scope:ws:paper.pdf"}""") =
        ExecutableToolCall(
            "c",
            ReadPdfTool.NAME,
            "1",
            Json.parseToJsonElement(args).jsonObject,
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
            "s",
            "t",
        )

    private val noImage = ToolImagePublication { _, _, _ -> error("Text pages must not publish pixels") }

    @Test fun textPagesAreBoundedPaginatedAndCarryDocumentIdentity() {
        val executor =
            ReadPdfTool.executor(
                PdfPageReader { _, path, page, image ->
                    assertEquals("scope:ws:paper.pdf", path)
                    assertEquals(1, page)
                    assertFalse(image)
                    PdfPage(2, hash, "Actual page text", false, true)
                },
                noImage,
            )
        val output = (executor.execute(call()) as ToolExecutorResult.Completed).output.jsonObject
        assertEquals("2", output["nextPage"]?.jsonPrimitive?.content)
        assertEquals(hash, output["sourceSha256"]?.jsonPrimitive?.content)
        assertEquals("false", output["eof"]?.jsonPrimitive?.content)
        assertEquals(ToolOperationClass.READ_ONLY, ReadPdfTool.descriptor().operationClass)
    }

    @Test fun visualPagesUseOrdinaryPublicationWithoutScreenGrant() {
        val visual = VisualArtifact("id", hash, "image/png", 5, 1, 1)
        val executor =
            ReadPdfTool.executor(
                PdfPageReader { _, _, page, force ->
                    assertEquals(2, page)
                    assertTrue(force)
                    PdfPage(2, hash, "", false, false, byteArrayOf(1, 2, 3))
                },
                ToolImagePublication { bound, bytes, scope ->
                    assertEquals("t", bound.turnId)
                    assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
                    assertNull(scope)
                    PublishedToolImage("scope:ws:output/page.png", hash, 3, visual)
                },
            )
        val result =
            executor.execute(
                call("""{"path":"scope:ws:paper.pdf","page":2,"mode":"image"}"""),
            ) as ToolExecutorResult.Completed
        assertEquals(visual, result.visualArtifact)
        assertEquals(
            "true",
            result.output.jsonObject["eof"]
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse(result.output.jsonObject.containsKey("nextPage"))
    }

    @Test fun unavailableVisionDoesNotClaimPageContentWasRead() {
        val executor =
            ReadPdfTool.executor(
                PdfPageReader { _, _, _, _ -> PdfPage(1, hash, "", false, false, byteArrayOf(1)) },
                ToolImagePublication {
                    _,
                    _,
                    _,
                    ->
                    PublishedToolImage("scope:ws:output/page.png", hash, 1, null, "VISION_UNAVAILABLE")
                },
            )
        val result = executor.execute(call()) as ToolExecutorResult.Completed
        assertNull(result.visualArtifact)
        assertEquals(
            "CONTENT_UNAVAILABLE",
            result.output.jsonObject["status"]
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(result.output.toString().contains("VISION_UNAVAILABLE"))
    }

    @Test fun invalidPathAndCancelledOrExpiredCallsNeverEnterReader() {
        val executor = ReadPdfTool.executor(PdfPageReader { _, _, _, _ -> error("Must not enter parser") }, noImage)
        for (path in listOf("/sdcard/secret.pdf", "scope:ws:../secret.pdf")) {
            assertTrue(executor.execute(call("""{"path":"$path"}""")) is ToolExecutorResult.Failed)
        }
        assertEquals(ToolExecutorResult.TimedOut, executor.execute(call().copy(deadline = Instant.EPOCH)))
        assertEquals(
            ToolExecutorResult.Cancelled,
            executor.execute(
                call().copy(
                    cancel =
                        object : com.helix.tools.framework.CancelSignal {
                            override fun isCancelled() = true
                        },
                ),
            ),
        )
    }

    @Test fun parserFailuresAreExplicitAndInvalidResultsNeverReachPublication() {
        for (reason in listOf(
            "PDF_INVALID",
            "PDF_ENCRYPTED_OR_RESTRICTED",
            "PAGE_OUT_OF_RANGE",
            "PARSER_DIED",
            "PDF_TOO_LARGE",
        )) {
            val result =
                ReadPdfTool
                    .executor(
                        PdfPageReader {
                            _,
                            _,
                            _,
                            _,
                            ->
                            throw PdfReadException(reason)
                        },
                        noImage,
                    ).execute(call())
            assertTrue(result is ToolExecutorResult.Failed)
            assertTrue((result as ToolExecutorResult.Failed).detail.contains(reason))
        }
        for (page in listOf(
            PdfPage(0, hash, "", false, true),
            PdfPage(
                1,
                hash,
                "x".repeat(PdfSourceSnapshot.MAX_TEXT + 1),
                true,
                true,
            ),
        )) {
            assertTrue(
                ReadPdfTool
                    .executor(
                        PdfPageReader {
                            _,
                            _,
                            _,
                            _,
                            ->
                            page
                        },
                        noImage,
                    ).execute(call()) is ToolExecutorResult.Failed,
            )
        }
    }

    @Test fun lateCancellationDiscardsResultAndTruncationIsNeverHidden() {
        var cancelled = false
        val executor =
            ReadPdfTool.executor(
                PdfPageReader { _, _, _, _ ->
                    cancelled = true
                    PdfPage(1, hash, "partial", true, true)
                },
                noImage,
            )
        assertEquals(
            ToolExecutorResult.Cancelled,
            executor.execute(
                call().copy(
                    cancel =
                        object : com.helix.tools.framework.CancelSignal {
                            override fun isCancelled() = cancelled
                        },
                ),
            ),
        )
        val completed =
            ReadPdfTool
                .executor(
                    PdfPageReader {
                        _,
                        _,
                        _,
                        _,
                        ->
                        PdfPage(1, hash, "partial", true, true)
                    },
                    noImage,
                ).execute(call())
        assertEquals(
            "true",
            (completed as ToolExecutorResult.Completed)
                .output.jsonObject["textTruncated"]
                ?.jsonPrimitive
                ?.content,
        )
    }
}
