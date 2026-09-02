package com.helix.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * HXA-044: [AtomicFileWriter.writeAtomicStream] — the streaming half of the atomic publish
 * contract (SAF imports must never stage multi-hundred-MiB content in memory).
 *
 * Axes covered:
 * - a clean stream is published atomically with exactly the streamed bytes;
 * - an [AbandonedWrite] (cancel or limit) aborts the publish: the temp is deleted, the target
 *   is absent, and the failure escapes so the caller can distinguish the abandonment reasons;
 * - a mid-stream I/O failure is likewise cleaned up and rethrown.
 */
class AtomicFileWriterStreamTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cleanStreamIsPublishedAtomicallyWithExactBytes() {
        val dir = tmp.newFolder("scope").toPath()
        val target = dir.resolve("data.bin")
        val payload = "streamed payload 0123456789".toByteArray()

        AtomicFileWriter.writeAtomicStream(target) { out -> out.write(payload) }

        assertTrue(Files.isRegularFile(target))
        assertEquals(payload.toList(), Files.readAllBytes(target).toList())
        // No orphan temp after a clean publish.
        assertEquals(0, AtomicFileWriter.cleanup(dir))
    }

    @Test
    fun cancelledAbandonDeletesTempAndPublishesNothing() {
        val dir = tmp.newFolder("scope").toPath()
        val target = dir.resolve("data.bin")

        val thrown =
            assertThrows(AbandonedWrite.Cancelled::class.java) {
                AtomicFileWriter.writeAtomicStream(target) { out ->
                    out.write("partial".toByteArray())
                    throw AbandonedWrite.Cancelled()
                }
            }
        assertEquals("write abandoned: cancelled", thrown.message)

        assertFalse("an abandoned stream must not publish the target", Files.exists(target))
        assertEquals("the temp must be deleted on abandonment", 0, AtomicFileWriter.cleanup(dir))
    }

    @Test
    fun limitExceededAbandonDeletesTempAndEscapes() {
        val dir = tmp.newFolder("scope").toPath()
        val target = dir.resolve("data.bin")

        assertThrows(AbandonedWrite.LimitExceeded::class.java) {
            AtomicFileWriter.writeAtomicStream(target) { out ->
                out.write(ByteArray(32))
                throw AbandonedWrite.LimitExceeded()
            }
        }

        assertFalse(Files.exists(target))
        assertEquals(0, AtomicFileWriter.cleanup(dir))
    }

    @Test
    fun midStreamIoFailureCleansTempAndRethrows() {
        val dir = tmp.newFolder("scope").toPath()
        val target = dir.resolve("data.bin")

        val failure = IOException("simulated provider reset")
        assertThrows(IOException::class.java) {
            AtomicFileWriter.writeAtomicStream(target) { out ->
                out.write("some bytes".toByteArray())
                out.flush()
                throw failure
            }
        }

        assertFalse(Files.exists(target))
        assertEquals(0, AtomicFileWriter.cleanup(dir))
    }

    @Test
    fun aCrashAbandonedTempIsStillReclaimedAfterAStreamFailure() {
        // Combines the two recovery stories: a previous crash left an orphan, and the current
        // stream then fails — the current temp is cleaned up and the orphan is still reclaimable.
        val dir = tmp.newFolder("scope").toPath()
        val target = dir.resolve("data.bin")
        Files.write(dir.resolve(".helix-tmp-crash-orphan"), "leftover".toByteArray())

        assertThrows(AbandonedWrite.Cancelled::class.java) {
            AtomicFileWriter.writeAtomicStream(target) { _ -> throw AbandonedWrite.Cancelled() }
        }

        assertEquals("only the crash orphan remains", 1, AtomicFileWriter.cleanup(dir))
        assertFalse(Files.exists(target))
    }

    @Test
    fun missingTargetParentIsRejected() {
        val dir = tmp.newFolder("scope").toPath()
        val missing = dir.resolve("nope").resolve("data.bin")
        assertThrows(IllegalArgumentException::class.java) {
            AtomicFileWriter.writeAtomicStream(missing) { out -> out.write("x".toByteArray()) }
        }
    }
}
