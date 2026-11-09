package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDisplayIdentityTest {
    @Test fun oldPlatformsAndUntrustedDisplaysReportSharedFocusWithoutUnlockFlags() {
        for (sdk in listOf(29, 30, 31, 33, 34, 36)) {
            for (trusted in listOf(false, true)) {
                val flags = backgroundDisplayFlags(sdk, trusted)
                assertEquals(0, flags and (1 shl 8))
                assertEquals(0, flags and (1 shl 12))
                assertEquals(0, flags and (1 shl 4))
                val independent = trusted && sdk >= 34
                assertEquals(independent, flags and (1 shl 14) != 0)
                assertEquals(independent, flags and (1 shl 16) != 0)
                assertTrue(flags and (1 shl 3) != 0)
            }
        }
    }

    @Test fun reusedNumericDisplayAndWindowIdsCannotRetargetAnOldFrame() {
        val target =
            AutomationDisplayTarget(
                "fixture.app",
                7,
                3,
                720,
                1280,
                0,
                AutomationNodeBounds(0, 0, 720, 1280),
                "first-display",
            )
        val registry = AutomationFrameRegistry { "frame" }
        registry.issue("grant", target)
        assertFalse(target.sameWindow(target.copy(displayId = 0, displaySession = "")))
        assertFalse(target.sameWindow(target.copy(displaySession = "replacement-display")))
        assertNull(registry.resolve("frame", "grant", target.copy(displaySession = "replacement-display")))
        assertEquals(target, registry.resolve("frame", "grant", target)?.target)
    }
}
