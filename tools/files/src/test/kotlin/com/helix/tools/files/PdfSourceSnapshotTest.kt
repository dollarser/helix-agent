package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant

class PdfSourceSnapshotTest {
    private fun call() =
        ExecutableToolCall(
            "c",
            ReadPdfTool.NAME,
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
        )

    @Test fun snapshotHashMatchesExactBytesAndRejectsNonPdf() {
        val bytes = "%PDF-1.7\nfixture".toByteArray()
        val output = ByteArrayOutputStream()
        val hash = PdfSourceSnapshot.copy(call(), bytes.inputStream(), output)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, hash)
        assertEquals(
            "NOT_A_PDF",
            assertThrows(PdfReadException::class.java) {
                PdfSourceSnapshot.copy(call(), "plain text".byteInputStream(), ByteArrayOutputStream())
            }.code,
        )
    }

    @Test fun sourceLimitRejectsBeforeWritingTheOverflowChunk() {
        var position = 0L
        val input =
            object : InputStream() {
                override fun read() = error("Buffered only")

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    val count = minOf(length.toLong(), PdfSourceSnapshot.MAX_BYTES + 1 - position).toInt()
                    if (count == 0) return -1
                    buffer.fill(0, offset, offset + count)
                    if (position == 0L) "%PDF-".toByteArray().copyInto(buffer, offset)
                    position += count
                    return count
                }
            }
        var written = 0L
        val output =
            object : java.io.OutputStream() {
                override fun write(value: Int) {
                    written++
                }

                override fun write(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    written += length
                }
            }
        assertEquals(
            "PDF_TOO_LARGE",
            assertThrows(PdfReadException::class.java) {
                PdfSourceSnapshot.copy(call(), input, output)
            }.code,
        )
        assertEquals(PdfSourceSnapshot.MAX_BYTES, written)
    }

    @Test fun cancellationAndDeadlineInterruptCopyAndNeverProduceAHash() {
        var reads = 0
        val input =
            object : InputStream() {
                override fun read() = error("Buffered only")

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    reads++
                    "%PDF-".toByteArray().copyInto(buffer, offset)
                    return 5
                }
            }
        val bound =
            call().copy(
                cancel =
                    object : CancelSignal {
                        override fun isCancelled() = reads > 0
                    },
            )
        assertEquals(
            "CANCELLED",
            assertThrows(PdfReadException::class.java) {
                PdfSourceSnapshot.copy(bound, input, ByteArrayOutputStream())
            }.code,
        )
        assertEquals(1, reads)
        assertEquals(
            "TIMED_OUT",
            assertThrows(PdfReadException::class.java) {
                PdfSourceSnapshot.copy(call().copy(deadline = Instant.EPOCH), input, ByteArrayOutputStream())
            }.code,
        )
        assertEquals(1, reads)
    }
}
