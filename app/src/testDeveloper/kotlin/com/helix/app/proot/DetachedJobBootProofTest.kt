package com.helix.app.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedJobBootProofTest {
    @Test fun missingInvalidSameOrRegressedBootNeverProvesStopped() {
        listOf(null to 2, 1 to null, -1 to 2, 2 to 2, 2 to 1).forEach { (before, after) ->
            assertFalse(DetachedJobBootProof.provesStopped(before, after))
        }
        assertTrue(DetachedJobBootProof.provesStopped(0, 1))
        assertTrue(DetachedJobBootProof.provesStopped(4, 6))
    }
}
