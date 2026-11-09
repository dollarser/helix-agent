package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableCoordinateTargetTest {
    private val target = AutomationDisplayTarget("fixture", 1, 0, 100, 200, 0, AutomationNodeBounds(0, 0, 100, 200))

    @Test fun stableWindowDoesNotWaitForChangingAppContent() {
        var time = 0L
        var contentFrames = 0
        val result =
            stableCoordinateTarget(
                {
                    contentFrames++
                    target
                },
                { true },
                { time },
                { time += it },
            )
        assertEquals(target, result)
        assertEquals(100L, time)
        assertEquals(3, contentFrames)
    }

    @Test fun lateAnimationBoundsAndWindowReplacementRestartTheStabilityInterval() {
        for (transitional in listOf(target.copy(bounds = target.bounds.copy(top = 2)), target.copy(windowId = 2))) {
            var time = 0L
            val result =
                stableCoordinateTarget(
                    { if (time < 100) transitional else target },
                    { true },
                    { time },
                    { time += it },
                )
            assertEquals(target, result)
            assertEquals(200L, time)
        }
    }

    @Test fun missingOrContinuouslyMovingWindowsCannotPublishAFrame() {
        for (missing in listOf(false, true)) {
            var time = 0L
            val result =
                stableCoordinateTarget(
                    { if (missing) null else target.copy(windowId = (time / 50).toInt()) },
                    { true },
                    { time },
                    { time += it },
                )
            assertNull(result)
            assertEquals(1_000L, time)
        }
    }

    @Test fun revocationAndOverdueMetadataReadsCannotPublish() {
        var time = 0L
        var reads = 0
        assertNull(
            stableCoordinateTarget(
                {
                    reads++
                    target
                },
                { time < 50 },
                { time },
                { time += it },
            ),
        )
        assertEquals(1, reads)
        assertNull(
            stableCoordinateTarget(
                {
                    time += 1_000
                    target
                },
                { true },
                { time },
                { error("No sleep after deadline") },
            ),
        )
    }
}
