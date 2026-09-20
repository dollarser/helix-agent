package com.helix.core.storage.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class SessionExportWriterTest {
    @Test fun productionFileCapIsEnforcedWithoutAccumulatingOutputInMemory() {
        var bytes = 0L
        val sink =
            object : OutputStream() {
                override fun write(value: Int) {
                    bytes++
                }

                override fun write(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    bytes += length
                }
            }
        val writer = SessionExportWriter(sink, "export", "session")
        writer.append(SessionExportType.HEADER, "header:session", buildJsonObject {})
        writer.append(SessionExportType.SESSION, "session:session", buildJsonObject {})
        val body = buildJsonObject { put("text", "x".repeat(64 * 1024)) }
        assertThrows(IllegalArgumentException::class.java) {
            repeat(3000) { writer.append(SessionExportType.MESSAGE, "message:$it", body) }
        }
        org.junit.Assert.assertTrue(bytes <= SessionExportFormat.FILE_BYTES)
        org.junit.Assert.assertTrue(bytes > SessionExportFormat.FILE_BYTES - SessionExportFormat.LINE_BYTES)
        assertThrows(IllegalStateException::class.java) { writer.finish(buildJsonObject {}) }
    }

    @Test fun unicodeEscapingStableIdsAndTailCountsSurviveIndependentParsing() {
        val output = ByteArrayOutputStream()
        val writer = SessionExportWriter(output, "export-1", "session-1")
        writer.append(SessionExportType.HEADER, "header:session-1", buildJsonObject {})
        writer.append(SessionExportType.SESSION, "session:session-1", buildJsonObject {})
        writer.append(
            SessionExportType.MESSAGE,
            "message:message-1",
            buildJsonObject {
                put("text", "中文\n\"quoted\"\\path\u0000😀")
                put("tokens", JsonNull)
            },
        )
        writer.finish(buildJsonObject {})
        val lines =
            output
                .toString(Charsets.UTF_8.name())
                .lineSequence()
                .filter(String::isNotEmpty)
                .toList()
        assertEquals(4, lines.size)
        val records = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf(0L, 1L, 2L, 3L), records.map { it.getValue("sequence").jsonPrimitive.long })
        val message = records[2]
        assertEquals("message:message-1", message.getValue("recordId").jsonPrimitive.content)
        assertEquals(
            "中文\n\"quoted\"\\path\u0000😀",
            message
                .getValue("data")
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content,
        )
        assertEquals(JsonNull, message.getValue("data").jsonObject["tokens"])
        val tail = records.last().getValue("data").jsonObject
        assertEquals(4L, tail.getValue("recordCount").jsonPrimitive.long)
        assertEquals(
            4L,
            tail
                .getValue("counts")
                .jsonObject.values
                .sumOf { it.jsonPrimitive.long },
        )
        assertThrows(IllegalStateException::class.java) { writer.finish(buildJsonObject {}) }
    }

    @Test fun lineAndTotalLimitsFailWithoutWritingACompletionRecord() {
        val output = ByteArrayOutputStream()
        val writer = SessionExportWriter(output, "e", "s", maxBytes = 1024)
        writer.append(SessionExportType.HEADER, "header:s", buildJsonObject {})
        assertThrows(IllegalArgumentException::class.java) {
            writer.append(SessionExportType.SESSION, "session:s", buildJsonObject { put("title", "a".repeat(1024)) })
        }
        assertFalse(output.toString().contains("\"complete\""))
        val lineWriter = SessionExportWriter(ByteArrayOutputStream(), "e", "s")
        assertThrows(IllegalArgumentException::class.java) {
            lineWriter.append(
                SessionExportType.HEADER,
                "header:s",
                buildJsonObject { put("text", "\u0000".repeat(SessionExportFormat.LINE_BYTES / 6)) },
            )
        }
    }

    @Test fun partialWriteFailureCannotBeRetriedOnTheSameWriter() {
        val sink =
            object : OutputStream() {
                override fun write(value: Int): Unit = throw IOException("synthetic destination failure")
            }
        val writer = SessionExportWriter(sink, "e", "s")
        assertThrows(IOException::class.java) {
            writer.append(SessionExportType.HEADER, "header:s", buildJsonObject {})
        }
        assertThrows(IllegalStateException::class.java) {
            writer.append(SessionExportType.HEADER, "header:s", buildJsonObject {})
        }
    }
}
