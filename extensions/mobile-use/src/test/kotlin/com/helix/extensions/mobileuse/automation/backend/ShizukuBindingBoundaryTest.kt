package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ShizukuBindingBoundaryTest {
    @Test fun connectionFailureNeverReachesTheDeviceTransaction() {
        var executed = false
        assertThrows(ShizukuBindingFailure::class.java) {
            executeAfterShizukuBinding<Any, Unit>({ error("No user service") }) { executed = true }
        }
        assertFalse(executed)
    }

    @Test fun anExecutionFailureIsNeverReclassifiedAsAPreDispatchRefusal() {
        val original = IllegalStateException("lost acknowledgement")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                executeAfterShizukuBinding({ "bound" }) { throw original }
            }
        assertSame(original, failure)
    }
}
