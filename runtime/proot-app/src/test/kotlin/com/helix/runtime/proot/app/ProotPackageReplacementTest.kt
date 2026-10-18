package com.helix.runtime.proot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotPackageReplacementTest {
    @Test fun modernAndroidRequiresTheSpecificSystemPackageUpdateReason() {
        assertTrue(isPackageReplacementExit(34, 16, 10_000, 10_001))
        for (reason in listOf(0, 2, 3, 4, 5, 10, 15)) {
            assertFalse(isPackageReplacementExit(36, reason, 10_000, 10_001))
        }
    }

    @Test fun olderAndroidRequiresTheSystemPackageKillNearTheActualInstall() {
        assertTrue(isPackageReplacementExit(30, 10, 10_000, 10_001))
        assertTrue(isPackageReplacementExit(33, 10, 10_000, 10_001))
        assertFalse(isPackageReplacementExit(33, 10, 10_000, 100_000))
        assertFalse(isPackageReplacementExit(33, 2, 10_000, 10_001))
        assertFalse(isPackageReplacementExit(29, 10, 10_000, 10_001))
    }
}
