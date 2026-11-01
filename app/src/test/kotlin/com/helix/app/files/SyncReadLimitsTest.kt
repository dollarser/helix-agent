package com.helix.app.files

import com.helix.core.workspace.ScopeRootResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class SyncReadLimitsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun unknownSizeIsBoundedByActualReadAndStreamClosesOnFailure() {
        val closed = AtomicBoolean()
        val backend = fixture(32 * 1024 * 1024 + 1, closed)
        assertThrows(IllegalArgumentException::class.java) {
            boundedSyncBackend(backend).read("file").use { stream ->
                val buffer = ByteArray(65536)
                while (stream.read(buffer) >= 0) Unit
            }
        }
        assertTrue(closed.get())
    }

    @Test fun exactBoundaryIsReadableWithoutTrustingProviderSize() {
        val closed = AtomicBoolean()
        var count = 0L
        boundedSyncBackend(fixture(32 * 1024 * 1024, closed)).read("file").use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                count += n
            }
        }
        assertEquals(32L * 1024 * 1024, count)
        assertTrue(closed.get())
    }

    private fun fixture(
        bytes: Int,
        closed: AtomicBoolean,
    ): ManualFileBackend {
        val nio = NioManualFileBackend("app", ScopeRootResolver { temp.root.toPath() }, false) {}
        return object : ManualFileBackend by nio {
            override fun stat(path: String) = ManualFileInfo(false, -1)

            override fun read(path: String) =
                object : InputStream() {
                    var remaining = bytes

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining == 0) return -1
                        val n = minOf(length, remaining)
                        buffer.fill(0, offset, offset + n)
                        remaining -= n
                        return n
                    }

                    override fun close() {
                        closed.set(true)
                    }
                }
        }
    }
}
