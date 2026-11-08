package com.helix.app.proot

import androidx.test.core.app.ApplicationProvider
import com.helix.provider.api.wire.WireBody
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Offline only: synthetic bytes, no account or network. */
class SyntheticSseCaptureDeviceTest {
    @Test
    fun splitUtf8KeepsBytesStopAndCompletionEvidence() =
        runBlocking {
            val directory = directory()
            val raw = stream("中文")
            val original = raw.toByteArray()
            val chunks = original.map { byteArrayOf(it) }
            val source = Body(chunks + listOf(byteArrayOf(1)))
            val capture = SyntheticSseCapture(source, directory, 0)
            try {
                var position = 0
                capture.forEachChunk {
                    assertSame(chunks[position++], it)
                    position < chunks.size
                }
                capture.close()
                capture.close()
                assertEquals(chunks.size, source.delivered)
                assertEquals(1, source.closes)
                assertEquals(raw, File(directory, "response-0.sse.txt").readText())
                val facts = JSONObject(File(directory, "response-0.json").readText())
                assertTrue(facts.getBoolean("completeObservedDelivery"))
                assertTrue(facts.getBoolean("consumerStopped"))
                assertTrue(facts.getBoolean("savedTextCanReconstructOriginalChunks"))
                val replay = facts.getJSONObject("decoderReplay")
                assertTrue(replay.getBoolean("protocolEnded"))
                assertEquals("stop", replay.getJSONArray("completedReasons").getString(0))
                assertEquals("中文", replay.getString("text"))
            } finally {
                capture.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun wholeBodyRedactionCoversCredentialSplitAcrossChunks() =
        runBlocking {
            val directory = directory()
            val synthetic = "sk-" + "a".repeat(24)
            val bytes = stream(synthetic).toByteArray()
            val source = Body(bytes.map { byteArrayOf(it) })
            val capture = SyntheticSseCapture(source, directory, 0)
            try {
                capture.forEachChunk { true }
                capture.close()
                assertFalse(File(directory, "response-0.sse.txt").readText().contains(synthetic))
                val rawFacts = File(directory, "response-0.json").readText()
                assertFalse(rawFacts.contains(synthetic))
                val facts = JSONObject(rawFacts)
                assertTrue(facts.getBoolean("redactionChanged"))
                assertFalse(facts.getBoolean("savedTextCanReconstructOriginalChunks"))
            } finally {
                capture.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun clippingAndDiagnosticWriteFailureNeverChangeConsumerOrException() =
        runBlocking {
            val directory = directory()
            val chunk = stream("ok").toByteArray()
            val expected = IOException("synthetic consumer failure")
            val source = Body(listOf(chunk, byteArrayOf(0)))
            val capture = SyntheticSseCapture(source, directory, 0, maxBytes = 1)
            try {
                val caught = runCatching { capture.forEachChunk { throw expected } }.exceptionOrNull()
                assertSame(expected, caught)
                capture.close()
                assertEquals(1, source.delivered)
                val facts = JSONObject(File(directory, "response-0.json").readText())
                assertTrue(facts.getBoolean("captureClipped"))
                assertFalse(facts.getBoolean("completeObservedDelivery"))
                assertEquals("IOException", facts.getString("streamFailureClass"))
                val blockedPath = File(directory, "not-a-directory").apply { writeText("fixture") }
                val second = Body(listOf(chunk))
                val blocked = SyntheticSseCapture(second, blockedPath, 0)
                blocked.forEachChunk {
                    assertSame(chunk, it)
                    false
                }
                blocked.close()
                assertEquals(1, second.delivered)
                assertEquals(1, second.closes)
                val closeFailure = IOException("synthetic close failure")
                val throwing = Body(listOf(chunk), closeFailure)
                val closing = SyntheticSseCapture(throwing, directory, 1)
                closing.forEachChunk { false }
                assertSame(closeFailure, runCatching { closing.close() }.exceptionOrNull())
                assertEquals(1, throwing.closes)
            } finally {
                capture.close()
                directory.deleteRecursively()
            }
        }

    private fun directory(): File =
        File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "sse-capture-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }

    private fun stream(text: String): String =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"

    private class Body(
        private val chunks: List<ByteArray>,
        private val closeFailure: IOException? = null,
    ) : WireBody {
        var delivered = 0
        var closes = 0

        override suspend fun bytes(): ByteArray = chunks.fold(byteArrayOf()) { value, chunk -> value + chunk }

        override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
            for (chunk in chunks) {
                delivered++
                if (!onChunk(chunk)) break
            }
        }

        override fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }
}
