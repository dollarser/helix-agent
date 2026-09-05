package com.helix.app.runcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidResourceGateTest {
    @Test
    fun foregroundHealthyAllowsTwoAndEveryPressureLowersToOne() {
        val probe = FakeProbe()
        val gate = AndroidResourceGate(probe)
        assertEquals(2, gate.allowance())

        probe.foreground = false
        assertEquals(1, gate.allowance())
        probe.foreground = true
        probe.lowMemory = true
        assertEquals(1, gate.allowance())
        probe.lowMemory = false
        probe.thermal = true
        assertEquals(1, gate.allowance())
    }

    @Test
    fun recoveryIsSampledAndRestoresOnlyTheNormalAllowance() {
        val probe = FakeProbe(lowMemory = true)
        val gate = AndroidResourceGate(probe)
        assertEquals(1, gate.allowance())
        probe.lowMemory = false
        assertEquals(2, gate.allowance())
        probe.foreground = false
        assertEquals(1, gate.allowance())
        probe.foreground = true
        assertEquals(2, gate.allowance())
    }

    private data class FakeProbe(
        var foreground: Boolean = true,
        var lowMemory: Boolean = false,
        var thermal: Boolean = false,
    ) : DeviceResourceProbe {
        override fun isForeground() = foreground

        override fun isLowMemory() = lowMemory

        override fun isThermallyConstrained() = thermal
    }
}
