package com.helix.core.storage.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class PreparedSessionExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun successfulCopyClosesTargetAndCleanupOnlyRemovesOwnedFile() {
        val bytes = "synthetic export".toByteArray()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val unrelated = temporary.newFile()
        var closed = false
        val output =
            object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                }
            }
        PreparedSessionExport(file, "id", bytes.size.toLong(), digest(bytes)).use { prepared ->
            prepared.deliver(output, {})
            assertEquals("synthetic export", output.toString("UTF-8"))
            assertEquals(true, closed)
        }
        assertFalse(file.exists())
        assertEquals(true, unrelated.exists())
    }

    @Test fun closeFailureCancellationAndChangedTemporaryDataCannotReportDeliverySuccess() {
        val bytes = "synthetic export".toByteArray()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        PreparedSessionExport(file, "id", bytes.size.toLong(), digest(bytes)).use { prepared ->
            assertThrows(IOException::class.java) {
                prepared.deliver(
                    object : ByteArrayOutputStream() {
                        override fun close(): Unit = throw IOException("synthetic close failure")
                    },
                    {},
                )
            }
            assertThrows(CancellationException::class.java) {
                prepared.deliver(ByteArrayOutputStream(), { throw CancellationException() })
            }
            file.writeText("x".repeat(bytes.size))
            assertThrows(IllegalStateException::class.java) { prepared.deliver(ByteArrayOutputStream(), {}) }
        }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
