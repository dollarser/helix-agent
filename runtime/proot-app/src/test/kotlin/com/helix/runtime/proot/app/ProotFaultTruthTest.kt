package com.helix.runtime.proot.app

import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProotFaultTruthTest {
    @Test fun everyTerminalIntentStaysUnknownWhileProcessLives() {
        ProotJobState.entries.filter { it.isTerminal }.forEach {
            assertEquals(ProotJobState.ORPHANED, ProotExecutionExit.terminalState(it, true))
            assertEquals(it, ProotExecutionExit.terminalState(it, false))
        }
    }

    @Test fun interruptedCleanupWaitsForActualExit() {
        val alive = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val restored = AtomicBoolean()
        val worker =
            Thread {
                Thread.currentThread().interrupt()
                ProotExecutionExit.awaitExit(alive::get, { entered.countDown() }, { release.await() })
                restored.set(Thread.currentThread().isInterrupted)
                done.countDown()
            }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(done.await(30, TimeUnit.MILLISECONDS))
            worker.interrupt()
            assertFalse(done.await(30, TimeUnit.MILLISECONDS))
            alive.set(false)
            release.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(restored.get())
        } finally {
            alive.set(false)
            release.countDown()
            worker.join(2000)
        }
    }

    @Test fun corruptJournalCannotMasqueradeAsNeverSubmitted() {
        val root = Files.createTempDirectory("proot-journal").toFile()
        try {
            val store = ProotJobStore(root)
            val id = "job_abcdef123456"
            assertNull(store.load(id))
            store.recordFile(id).apply {
                requireNotNull(parentFile).mkdirs()
                writeText("{truncated")
            }
            assertThrows(IllegalStateException::class.java) { store.load(id) }
            assertThrows(IllegalStateException::class.java) { store.entries() }
            assertEquals("{truncated", store.recordFile(id).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun stoppedInputNeverCopiesOrStartsAJob() {
        val output = java.io.ByteArrayOutputStream()
        assertThrows(java.io.IOException::class.java) {
            copyJobInput("fixture".byteInputStream(), output) { true }
        }
        assertEquals(0, output.size())
        copyJobInput("fixture".byteInputStream(), output) { false }
        assertEquals("fixture", output.toString("UTF-8"))
    }
}
