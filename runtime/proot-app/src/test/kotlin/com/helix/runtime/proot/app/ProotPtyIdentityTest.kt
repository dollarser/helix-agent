package com.helix.runtime.proot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class ProotPtyIdentityTest {
    @Test fun hiddenChildBecomesVisibleWithoutChangingItsIdentity() {
        var reads = 0
        var elapsed = 0L
        val identity =
            awaitPtyProcessIdentity(
                42,
                exitObserved = { false },
                readStat = { pid ->
                    assertEquals(42, pid)
                    if (++reads < 3) throw FileNotFoundException("hidden before exec")
                    stat(pid)
                },
                nanoTime = { elapsed },
                pause = { elapsed += it * 1_000_000 },
            )
        assertEquals(42, identity.pid)
        assertEquals(1234L, identity.startTicks)
        assertEquals(3, reads)
    }

    @Test fun permanentlyHiddenIdentityFailsWithinBoundedWait() {
        var elapsed = 0L
        assertThrows(FileNotFoundException::class.java) {
            awaitPtyProcessIdentity(
                42,
                exitObserved = { false },
                readStat = { throw FileNotFoundException("still hidden") },
                nanoTime = { elapsed },
                pause = { elapsed += it * 1_000_000 },
            )
        }
        assertEquals(5_000_000_000L, elapsed)
    }

    @Test fun observedExitDoesNotReadAnUnrelatedIdentityOrWait() {
        assertThrows(IllegalStateException::class.java) {
            awaitPtyProcessIdentity(
                42,
                exitObserved = { true },
                readStat = { error("must not read") },
                pause = { error("must not wait") },
            )
        }
    }

    @Test fun exitDuringVisibilityWaitDoesNotInventRunningState() {
        var reads = 0
        assertThrows(IllegalStateException::class.java) {
            awaitPtyProcessIdentity(
                42,
                exitObserved = { reads > 0 },
                readStat = {
                    reads++
                    throw FileNotFoundException("hidden")
                },
                pause = {},
            )
        }
        assertEquals(1, reads)
    }

    @Test fun mismatchedIdentityAndOtherIoFailuresAreNotRetried() {
        assertThrows(IllegalStateException::class.java) {
            awaitPtyProcessIdentity(42, { false }, { stat(43) }, pause = { error("must not retry") })
        }
        assertThrows(IOException::class.java) {
            awaitPtyProcessIdentity(
                42,
                { false },
                { throw IOException("read failed") },
                pause = { error("must not retry") },
            )
        }
    }

    @Test fun interruptedVisibilityWaitPropagates() {
        assertThrows(InterruptedException::class.java) {
            awaitPtyProcessIdentity(
                42,
                { false },
                { throw FileNotFoundException("hidden") },
                pause = { throw InterruptedException("cancelled") },
            )
        }
    }

    private fun stat(pid: Int) =
        "$pid (command with ) parentheses) " + List(20) { if (it == 19) "1234" else "0" }.joinToString(" ")
}
