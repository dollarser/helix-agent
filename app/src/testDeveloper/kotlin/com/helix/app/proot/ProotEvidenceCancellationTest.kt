package com.helix.app.proot

import com.helix.app.goal.readGoalEvidence
import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProotEvidenceCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cancellingCallerInterruptsArchiveReadAndCleansScratch() =
        runBlocking {
            val bytes = ByteArray(32768) { 42 }
            val archive = archive(bytes)
            val scratch = temporary.newFolder()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val returned = AtomicBoolean(false)
            val reading =
                async {
                    readGoalEvidence {
                        var checkpoints = 0
                        try {
                            ProotEvidenceContent
                                .read(archive, scratch, "result.txt") {
                                    if (++checkpoints == 4) {
                                        started.countDown()
                                        check(release.await(10, TimeUnit.SECONDS))
                                    }
                                    Thread.currentThread().isInterrupted
                                }.also { returned.set(true) }
                        } finally {
                            finished.countDown()
                        }
                    }
                }
            try {
                yield()
                assertTrue(started.await(10, TimeUnit.SECONDS))
                reading.cancel()
                assertTrue("cancel must stop IO before release", finished.await(3, TimeUnit.SECONDS))
                reading.join()
                assertFalse(returned.get())
                assertTrue(scratch.listFiles().orEmpty().isEmpty())
                assertArrayEquals(bytes, readGoalEvidence { ProotEvidenceContent.read(archive, scratch, "result.txt") })
            } finally {
                release.countDown()
                reading.cancelAndJoin()
            }
        }

    private fun archive(bytes: ByteArray): File {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val archive = temporary.newFile()
        val entry = JobManifestEntry("result.txt", FileContentStore.sha256Hex(bytes), bytes.size.toLong())
        JobZipWriter(archive.outputStream()).use {
            it.writeManifest(JobManifestCodec.encode(JobManifest(listOf(entry))))
            it.writeEntry("result.txt", file)
        }
        return archive
    }
}
