package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PtyPackageReplacementTest {
    @get:Rule val temporary = TemporaryFolder()
    private val initial =
        PtySessionRecord(PtySessionOrigin("terminal", "g1", "e1", "/workspace", "r1", 7, 10_000, 1000, 61_000))
    private val unknown = initial.started(PtyProcessIdentity(123, 42)).runtimeLost()
    private val proof = PtyPackageReplacement(7, 12_000, 11_999, 20_000, 11_000)

    @Test fun replacementAllowsExactSettlementWithoutChangingUnknownOutcomeOrExitStatus() {
        val recovered = unknown.afterPackageReplacement(proof)
        assertEquals(unknown.origin, recovered.origin)
        assertEquals(unknown.process, recovered.process)
        assertEquals(PtySessionRecord.Phase.UNKNOWN, recovered.phase)
        assertEquals(PtySessionRecord.StopReason.RUNTIME_LOST, recovered.stopReason)
        assertEquals(PtySessionRecord.StopProof.PACKAGE_REPLACED, recovered.stopProof)
        assertEquals(12_000L, recovered.stoppedAtPackageUpdateTime)
        assertNull(recovered.exitStatus)
        assertEquals(
            recovered.acknowledge(),
            PtySessionRecordCodec.decode(PtySessionRecordCodec.encode(recovered.acknowledge())),
        )
    }

    @Test fun equalOlderMissingAndFutureUpdateEvidenceCannotReleaseTheOriginalOwner() {
        listOf(
            proof.copy(updatedAtEpochMs = 10_000),
            proof.copy(updatedAtEpochMs = 9999),
            proof.copy(updatedAtEpochMs = 20_001),
            proof.copy(runtimeKilledAtEpochMs = 10_000),
            proof.copy(runtimeKilledAtEpochMs = 20_001),
            proof.copy(bootCount = null),
            proof.copy(bootCount = 6),
        ).forEach {
            assertFalse(it.provesExit(unknown.origin))
            assertThrows(IllegalStateException::class.java) { unknown.afterPackageReplacement(it) }
        }
        assertThrows(IllegalStateException::class.java) { unknown.acknowledge() }
    }

    @Test fun clockDiscontinuityAndDifferentBootCannotTurnLeaseExpiryIntoExitProof() {
        assertFalse(proof.copy(observedAtElapsedMs = 1_000_000).provesExit(unknown.origin))
        assertFalse(proof.copy(observedAtEpochMs = 1_000_000).provesExit(unknown.origin))
        assertFalse(proof.copy(bootCount = 8).provesExit(unknown.origin))
        assertEquals(PtySessionRecord.StopProof.DEVICE_REBOOT, unknown.afterReboot(8).stopProof)
        assertNull(unknown.afterReboot(8).stoppedAtPackageUpdateTime)
    }

    @Test fun newSessionAfterReplacementCannotConsumeOlderExitEvidence() {
        val later = initial.copy(origin = initial.origin.copy(createdAtEpochMs = 15_000, startedAtElapsedMs = 6000))
        assertFalse(proof.provesExit(later.origin))
        assertFalse(proof.copy(runtimeKilledAtEpochMs = 9000).provesExit(initial.origin))
    }

    @Test fun durableRecoveryIsIdempotentAndStaleCasCannotClearOrRewindIt() {
        val store = PtySessionStore(temporary.newFolder())
        val recovered = unknown.afterPackageReplacement(proof)
        assertTrue(store.compareAndSet(null, initial))
        val running = initial.started(checkNotNull(unknown.process))
        assertTrue(store.compareAndSet(initial, running))
        assertTrue(store.compareAndSet(running, unknown))
        assertTrue(store.compareAndSet(unknown, recovered))
        assertEquals(recovered, store.read("terminal"))
        assertFalse(store.compareAndSet(unknown, unknown.acknowledgeAfterReboot()))
        assertThrows(IllegalArgumentException::class.java) { store.compareAndSet(recovered, unknown) }
        assertTrue(store.compareAndSet(recovered, recovered.acknowledge()))
        assertTrue(store.compareAndSet(recovered.acknowledge(), recovered.acknowledge()))
        assertEquals(recovered.acknowledge(), store.read("terminal"))
    }

    @Test fun existingV1JournalsRemainReadableWithoutInventingReplacementProof() {
        val v2 = PtySessionRecordCodec.encode(unknown)
        val v1 = v2.copyOf(v2.size - 8).also { it[3] = 1 }
        assertEquals(unknown, PtySessionRecordCodec.decode(v1))
        assertNull(PtySessionRecordCodec.decode(v1).stopProof)
        assertEquals(
            unknown.afterPackageReplacement(proof),
            PtySessionRecordCodec.decode(v1).afterPackageReplacement(proof),
        )
    }

    private fun PtySessionRecord.acknowledgeAfterReboot() = afterReboot(8).acknowledge()
}
