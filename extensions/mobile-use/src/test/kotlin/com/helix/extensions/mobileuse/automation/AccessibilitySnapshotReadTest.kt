package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AccessibilitySnapshotReadTest {
    private var generation = 1L
    private var captures = 0
    private var invalidations = 0
    private var admitted = true

    private fun snapshot() =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot("fixture", 1, generation, Instant.EPOCH, emptyList(), false),
        )

    private fun read(capture: () -> AutomationSnapshotResult) =
        readStableAccessibilitySnapshot(
            capture = {
                captures++
                capture()
            },
            generation = { generation },
            admitted = { admitted },
            invalidate = { invalidations++ },
        )

    @Test
    fun stableObservationIsReturnedWithoutRetry() {
        assertEquals(1L, read(::snapshot).snapshot?.generation)
        assertEquals(1, captures)
        assertEquals(0, invalidations)
    }

    @Test
    fun expiredObservationIsInvalidatedAndReplacedByFreshCapture() {
        val result = read { snapshot().also { if (captures == 1) generation++ } }
        assertEquals(2L, result.snapshot?.generation)
        assertEquals(2, captures)
        assertEquals(1, invalidations)
    }

    @Test
    fun eventStormRemainsBoundedAndNeverPublishesExpiredNodes() {
        val result = read { snapshot().also { generation++ } }
        assertEquals(AutomationSnapshotStatus.TARGET_CHANGED, result.status)
        assertNull(result.snapshot)
        assertEquals(3, captures)
        assertEquals(3, invalidations)
    }

    @Test
    fun revokedAdmissionDoesNotCapture() {
        admitted = false
        assertEquals(AutomationSnapshotStatus.NO_ACTIVE_SESSION, read(::snapshot).status)
        assertEquals(0, captures)
    }

    @Test
    fun revocationDuringCaptureInvalidatesNodesWithoutRetry() {
        assertEquals(
            AutomationSnapshotStatus.NO_ACTIVE_SESSION,
            read { snapshot().also { admitted = false } }.status,
        )
        assertEquals(1, captures)
        assertEquals(1, invalidations)
    }

    @Test
    fun revocationBetweenAttemptsPreventsFurtherCapture() {
        val result =
            readStableAccessibilitySnapshot(
                capture = {
                    captures++
                    snapshot().also { generation++ }
                },
                generation = { generation },
                admitted = { admitted },
                invalidate = {
                    invalidations++
                    admitted = false
                },
            )
        assertEquals(AutomationSnapshotStatus.NO_ACTIVE_SESSION, result.status)
        assertEquals(1, captures)
        assertEquals(1, invalidations)
    }

    @Test
    fun targetAndPolicyFailuresAreNeverRetried() {
        for (status in listOf(
            AutomationSnapshotStatus.TARGET_CHANGED,
            AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED,
            AutomationSnapshotStatus.SENSITIVE_UI,
            AutomationSnapshotStatus.SERVICE_NOT_CONNECTED,
        )) {
            captures = 0
            assertEquals(status, read { AutomationSnapshotResult(status) }.status)
            assertEquals(1, captures)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun captureExceptionsPropagate() {
        read { error("capture failed") }
    }
}
