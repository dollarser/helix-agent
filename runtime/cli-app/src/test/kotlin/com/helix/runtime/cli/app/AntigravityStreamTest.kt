package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityStreamTest {
    @Test fun emptyTokenLimitedResponseRetainsItsTerminalReason() {
        val decoder = AntigravityStreamDecoder(emptyMap()) { _, _ -> error("No tool replay") }
        val events = decoder.feed(frame("[]", "MAX_TOKENS").toByteArray()) + decoder.finish()
        assertEquals("length", events.filterIsInstance<ModelEvent.Completed>().single().finishReason)
        assertFalse(events.any { it is ModelEvent.Error || it is ModelEvent.TextDelta })
    }

    @Test fun fragmentedUtf8AndCrLfProduceOneTerminal() {
        val decoder = AntigravityStreamDecoder(emptyMap()) { _, _ -> error("No tool replay") }
        val bytes = frame("""[{"text":"你好"}]""", "STOP").replace("\n", "\r\n").toByteArray()
        val events = bytes.flatMap { decoder.feed(byteArrayOf(it)) } + decoder.finish()
        assertEquals("你好", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(1, events.count { it is ModelEvent.Completed })
        assertTrue(decoder.finish().isEmpty())
    }

    @Test fun toolCallsWaitForStopAndDurableSignedReplay() {
        var saved = false
        val name = AntigravityRequest.wireName("files.read")
        val decoder =
            AntigravityStreamDecoder(mapOf(name to "files.read")) { _, parts ->
                assertTrue(parts.toString().contains("signature"))
                saved = true
            }
        val first =
            decoder.feed(
                frame(
                    """[{"functionCall":{"name":"$name","args":{}},"thoughtSignature":"signature"}]""",
                ).toByteArray(),
            )
        assertTrue(first.isEmpty())
        assertFalse(saved)
        val last = decoder.feed(frame("[]", "STOP").toByteArray())
        assertTrue(saved)
        assertEquals(1, last.count { it is ModelEvent.ToolCallStarted })
        assertEquals("tool_calls", (last.last() as ModelEvent.Completed).finishReason)
    }

    @Test fun missingStopAndLengthNeverReleasePendingTools() {
        val name = AntigravityRequest.wireName("files.read")
        listOf(false, true).forEach { length ->
            val decoder = AntigravityStreamDecoder(mapOf(name to "files.read")) { _, _ -> error("No replay") }
            val pending = decoder.feed(frame("""[{"functionCall":{"name":"$name","args":{}}}]""").toByteArray())
            val final = if (length) decoder.feed(frame("[]", "MAX_TOKENS").toByteArray()) else decoder.finish()
            assertFalse((pending + final).any { it is ModelEvent.ToolCallStarted })
            assertTrue(final.last() is ModelEvent.Error || final.last() is ModelEvent.Completed)
        }
    }

    @Test fun replayPersistenceFailureCannotPublishSuccessfulTools() {
        val name = AntigravityRequest.wireName("files.read")
        val decoder = AntigravityStreamDecoder(mapOf(name to "files.read")) { _, _ -> error("Disk failure") }
        val events =
            decoder.feed(
                frame(
                    """[{"functionCall":{"name":"$name","args":{}}}]""",
                    "STOP",
                ).toByteArray(),
            )
        assertTrue(events.last() is ModelEvent.Error)
        assertFalse(events.any { it is ModelEvent.ToolCallStarted })
    }

    @Test fun plainLongReplyIsNotLimitedByReplayHistoryOrTotalResponseBytes() {
        val decoder = AntigravityStreamDecoder(emptyMap()) { _, _ -> error("No tool replay") }
        val data = frame("""[{"text":"${"a".repeat(100000)}"}]""").toByteArray()
        var count = 0
        repeat(96) {
            val events = decoder.feed(data)
            assertFalse(events.any { it is ModelEvent.Error })
            count += events.filterIsInstance<ModelEvent.TextDelta>().sumOf { it.text.length }
        }
        assertEquals(9600000, count)
        assertTrue(decoder.feed(frame("[]", "STOP").toByteArray()).last() is ModelEvent.Completed)
    }

    @Test fun malformedOrOversizedFrameHasOneFailure() {
        listOf("data: {broken}\n\n".toByteArray(), ByteArray(2 * 1024 * 1024 + 1) { 65 }).forEach { bytes ->
            val decoder = AntigravityStreamDecoder(emptyMap()) { _, _ -> error("No replay") }
            val events = decoder.feed(bytes)
            assertEquals(1, events.filterIsInstance<ModelEvent.Error>().size)
            assertTrue(decoder.finish().isEmpty())
        }
    }

    @Test fun utf8SpoolEncodingPreservesEmojiAcrossDeltaBoundary() {
        val text = "a".repeat(65535) + "\uD83D\uDE80" + "tail"
        val encoded = antigravityChunks(text).map { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) }
        assertEquals(text, encoded.joinToString(""))
    }

    @Test fun finalStopFrameWithoutBlankSeparatorStillTerminatesOnce() {
        val decoder = AntigravityStreamDecoder(emptyMap()) { _, _ -> error("No replay") }
        val events = decoder.feed(frame("""[{"text":"done"}]""", "STOP").trimEnd().toByteArray()) + decoder.finish()
        assertEquals(1, events.filterIsInstance<ModelEvent.Completed>().size)
        assertFalse(events.any { it is ModelEvent.Error })
    }

    private fun frame(
        parts: String,
        reason: String? = null,
    ): String {
        val candidate =
            buildJsonObject {
                put(
                    "content",
                    buildJsonObject {
                        put(
                            "parts",
                            kotlinx.serialization.json.Json
                                .parseToJsonElement(parts),
                        )
                    },
                )
                reason?.let { put("finishReason", it) }
            }
        val root =
            buildJsonObject { put("response", buildJsonObject { put("candidates", JsonArray(listOf(candidate))) }) }
        return "data: $root\n\n"
    }
}
