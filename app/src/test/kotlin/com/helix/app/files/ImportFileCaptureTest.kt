package com.helix.app.files

import com.helix.core.workspace.ScopeRootResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ImportFileCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun boundedStreamNeverReturnsTruncatedSuccessAndClosesAtCaller() {
        val bytes = byteArrayOf(0, 1, 2, -1)
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).use { ImportFileCapture.read(it, 4) })
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(bytes).use { ImportFileCapture.read(it, 3) }
        }
    }

    @Test fun cancellationAndUnreadableOrNonProgressingStreamsRemainFailures() {
        var reads = 0
        val input =
            object : InputStream() {
                override fun read(): Int {
                    reads++
                    return 1
                }
            }
        assertThrows(CancellationException::class.java) { ImportFileCapture.read(input, 8) { true } }
        assertEquals(0, reads)
        val broken =
            object : InputStream() {
                override fun read(): Int = throw IOException("unavailable")
            }
        assertThrows(IOException::class.java) { ImportFileCapture.read(broken, 8) }
        val stalled =
            object : InputStream() {
                override fun read(): Int = 0

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        assertThrows(IOException::class.java) { ImportFileCapture.read(stalled, 8) }
    }

    @Test fun manualSelectionIsReadOnlyContainmentCheckedAndRechecksLiveAccess() {
        val root = temporary.newFolder().toPath()
        val file = root.resolve("probe.ZIP")
        Files.write(file, "synthetic".toByteArray())
        var readable = true
        val backend =
            NioManualFileBackend(
                "scope",
                ScopeRootResolver {
                    check(readable)
                    root
                },
                false,
            ) {
                error("Read-only import must not request write access")
            }
        val operations = ManualFileOperations({ backend }, { false })
        assertEquals("synthetic", String(operations.captureImport("scope", "probe.ZIP", 64) { false }))
        assertEquals(1L, Files.list(root).use { it.count() })
        readable = false
        assertThrows(IllegalStateException::class.java) { operations.captureImport("scope", "probe.ZIP", 64) { false } }
        readable = true
        assertThrows(
            IllegalArgumentException::class.java,
        ) { operations.captureImport("scope", "../outside", 64) { false } }
        assertThrows(Exception::class.java) { operations.captureImport("scope", "missing.zip", 64) { false } }
        assertEquals("synthetic", Files.readString(file))
    }

    @Test fun sameSizeExternalChangeDuringReadIsRejected() {
        var calls = 0
        val backend =
            object : ManualFileBackend {
                override fun stat(path: String) = ManualFileInfo(false, 4)

                override fun read(path: String): InputStream =
                    ByteArrayInputStream(
                        (
                            if (++calls ==
                                1
                            ) {
                                "first"
                            } else {
                                "other"
                            }
                        ).take(4).toByteArray(),
                    )

                override fun children(path: String) = emptyList<String>()

                override fun validateMutation(path: String) = error("no mutation")

                override fun create(
                    path: String,
                    directory: Boolean,
                ) = error("no mutation")

                override fun write(path: String) = error("no mutation")

                override fun rename(
                    path: String,
                    destination: String,
                ) = error("no mutation")

                override fun delete(path: String) = error("no mutation")
            }
        val operations = ManualFileOperations({ backend }, { false })
        assertThrows(IllegalStateException::class.java) { operations.captureImport("scope", "probe", 64) { false } }
        assertFalse(calls > 2)
    }
}
