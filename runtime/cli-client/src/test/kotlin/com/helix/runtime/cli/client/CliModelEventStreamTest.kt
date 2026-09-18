package com.helix.runtime.cli.client

import com.helix.core.model.ModelEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CliModelEventStreamTest {
    @Test fun fragmentedUtf8StreamMatchesTheExistingWireAndClosesInput() {
        val events = listOf(ModelEvent.TextDelta("你好🌍\n\"quoted\""), ModelEvent.Completed("stop"))
        val bytes = CliModelEventCodec.encode(events)
        var closed = false
        val input =
            object : ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, minOf(3, length))

                override fun close() {
                    closed = true
                }
            }
        assertEquals(events, CliModelEventStream.readVerified(input, cliPayloadSha256(bytes)))
        assertTrue(closed)
    }

    @Test fun rejectsBadHashTruncatedUnknownFieldsAndInvalidTerminalWithoutReturningEvents() {
        val valid = CliModelEventCodec.encode(listOf(ModelEvent.Completed("stop"))).decodeToString()
        val documents =
            listOf(
                valid.dropLast(1),
                valid.dropLast(1) + ",\"unknown\":true}",
                valid.replace("\"version\":1", "\"version\":2"),
                "{\"version\":1,\"events\":[]}",
                CliModelEventCodec.encode(listOf(ModelEvent.TextDelta("unterminated"))).decodeToString(),
                CliModelEventCodec
                    .encode(
                        listOf(ModelEvent.Completed("stop"), ModelEvent.TextDelta("late")),
                    ).decodeToString(),
                valid + " trailing",
            )
        documents.forEach { document ->
            val bytes = document.encodeToByteArray()
            assertThrows(RuntimeException::class.java) {
                CliModelEventStream.readVerified(bytes.inputStream(), cliPayloadSha256(bytes))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliModelEventStream.readVerified(valid.byteInputStream(), "0".repeat(64))
        }
    }
}
