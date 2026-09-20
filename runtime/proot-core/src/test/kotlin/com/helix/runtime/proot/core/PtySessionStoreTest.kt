package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PtySessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun intent(id: String = "terminal") =
        PtySessionRecord(
            PtySessionOrigin(id, "generation", "execution", "/workspace/中文", "runtime", 7, 1000, 100, 60100),
        )

    @Test fun interruptedLaunchIsUnknownEvenWithoutSavedPidAndCannotBeAcknowledged() {
        val root = temporary.newFolder()
        val original = intent()
        assertTrue(PtySessionStore(root).compareAndSet(null, original))
        val reopened = PtySessionStore(root)
        val recovered = checkNotNull(reopened.read("terminal")).runtimeLost()
        assertEquals(PtySessionRecord.Phase.UNKNOWN, recovered.phase)
        assertNull(recovered.process)
        assertNull(recovered.stopProof)
        assertThrows(IllegalStateException::class.java) { recovered.acknowledge() }
        assertTrue(reopened.compareAndSet(original, recovered))
        assertThrows(IllegalArgumentException::class.java) { reopened.compareAndSet(recovered, original) }
        assertFalse(reopened.compareAndSet(null, original))
    }

    @Test fun rebootProofMustStrictlyAdvanceAndPreservesUnknownOutcome() {
        val unknown = intent().started(PtyProcessIdentity(100, 42)).runtimeLost()
        listOf(null, 6, 7).forEach { boot ->
            assertThrows(IllegalStateException::class.java) { unknown.afterReboot(boot) }
        }
        val recovered = unknown.afterReboot(8)
        assertEquals(PtySessionRecord.Phase.UNKNOWN, recovered.phase)
        assertEquals(PtySessionRecord.StopProof.DEVICE_REBOOT, recovered.stopProof)
        assertEquals(8, recovered.stoppedAtBootCount)
        assertNull(recovered.exitStatus)
        assertEquals(
            recovered.acknowledge(),
            PtySessionRecordCodec.decode(PtySessionRecordCodec.encode(recovered.acknowledge())),
        )
        val missingBoot = intent().copy(origin = intent().origin.copy(bootCount = null)).runtimeLost()
        assertThrows(IllegalStateException::class.java) { missingBoot.afterReboot(8) }
    }

    @Test fun cancellationDuringForkKeepsItsReasonWhenProcessIdentityArrives() {
        val root = temporary.newFolder()
        val store = PtySessionStore(root)
        val original = intent()
        val cancelling = original.requestStop(PtySessionRecord.StopReason.USER)
        val bound = cancelling.started(PtyProcessIdentity(123, 10))
        assertEquals(PtySessionRecord.Phase.CLOSING, bound.phase)
        assertEquals(PtySessionRecord.StopReason.USER, bound.stopReason)
        assertTrue(store.compareAndSet(null, original))
        assertTrue(store.compareAndSet(original, cancelling))
        assertTrue(store.compareAndSet(cancelling, bound))
        assertFalse(store.compareAndSet(original, original.started(PtyProcessIdentity(124, 11))))
        assertEquals(bound, PtySessionStore(root).read("terminal"))
        val stopped = bound.stoppedTree(265)
        assertTrue(store.compareAndSet(bound, stopped))
        assertEquals(stopped, stopped.runtimeLost())
        assertThrows(IllegalArgumentException::class.java) { store.compareAndSet(stopped, bound) }
        assertThrows(IllegalArgumentException::class.java) { store.removeReconciled(stopped) }
        assertTrue(store.compareAndSet(stopped, stopped.acknowledge()))
        assertTrue(store.removeReconciled(stopped.acknowledge()))
        assertNull(store.read("terminal"))
    }

    @Test fun originAndProcessIdentityCannotBeRewrittenByStaleOrDifferentRequest() {
        val store = PtySessionStore(temporary.newFolder())
        val original = intent()
        val running = original.started(PtyProcessIdentity(123, 10))
        assertTrue(store.compareAndSet(null, original))
        assertTrue(store.compareAndSet(original, running))
        assertThrows(IllegalArgumentException::class.java) {
            store.compareAndSet(running, running.copy(origin = original.origin.copy(workspace = "/other")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.compareAndSet(running, running.copy(process = PtyProcessIdentity(123, 11)))
        }
        assertThrows(IllegalArgumentException::class.java) { store.compareAndSet(running, original) }
        assertEquals(running, store.read("terminal"))
        assertThrows(IllegalArgumentException::class.java) { store.read("../terminal") }
    }

    @Test fun corruptOversizedTruncatedAndForeignFilesNeverBecomeMissingRecords() {
        val root = temporary.newFolder()
        val store = PtySessionStore(root)
        val file = File(root, "terminal.pty")
        val encoded = PtySessionRecordCodec.encode(intent())
        listOf(byteArrayOf(), encoded.copyOf(7), encoded + 1, ByteArray(PtySessionRecordCodec.MAX_BYTES + 1)).forEach {
            file.writeBytes(it)
            assertThrows(Exception::class.java) { store.read("terminal") }
        }
        file.writeBytes(encoded.copyOf().also { it[it.size - 5] = 2 })
        assertThrows(IllegalArgumentException::class.java) { store.read("terminal") }
        file.writeBytes(encoded.copyOf().also { it[3] = 99 })
        assertThrows(IllegalArgumentException::class.java) { store.read("terminal") }
        file.writeBytes(PtySessionRecordCodec.encode(intent("other")))
        assertThrows(IllegalStateException::class.java) { store.read("terminal") }
        assertThrows(IllegalStateException::class.java) { store.records() }
        val blockedDirectory = temporary.newFile()
        assertThrows(IllegalStateException::class.java) { PtySessionStore(blockedDirectory).read("terminal") }
    }

    @Test fun capacityNeverEvictsUnreconciledOrUnknownEvidence() {
        val root = temporary.newFolder()
        val store = PtySessionStore(root)
        repeat(PtySessionStore.MAX_ENTRIES) { assertTrue(store.compareAndSet(null, intent("s$it"))) }
        assertThrows(IllegalStateException::class.java) { store.compareAndSet(null, intent("overflow")) }
        assertEquals(PtySessionStore.MAX_ENTRIES, store.records().size)
        val original = intent("s0")
        val stopped = original.neverStarted()
        assertTrue(store.compareAndSet(original, stopped))
        assertTrue(store.compareAndSet(stopped, stopped.acknowledge()))
        assertTrue(store.removeReconciled(stopped.acknowledge()))
        assertTrue(store.compareAndSet(null, intent("new")))
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".pending") })
    }

    @Test(timeout = 10_000)
    fun concurrentWrappersCannotLoseTheWinningStopRequest() {
        val root = temporary.newFolder()
        val original = intent()
        assertTrue(PtySessionStore(root).compareAndSet(null, original))
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val requests =
                listOf(PtySessionRecord.StopReason.USER, PtySessionRecord.StopReason.LEASE_EXPIRED).map {
                    val next = original.requestStop(it)
                    pool.submit<Pair<Boolean, PtySessionRecord>> {
                        ready.await()
                        PtySessionStore(root).compareAndSet(original, next) to next
                    }
                }
            ready.countDown()
            val results = requests.map { it.get(2, TimeUnit.SECONDS) }
            val winner = results.single { it.first }.second
            assertEquals(winner, PtySessionStore(root).read("terminal"))
            assertEquals(1, results.count { !it.first })
        } finally {
            ready.countDown()
            pool.shutdownNow()
        }
    }
}
