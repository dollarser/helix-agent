package com.helix.app.runcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidResourceGateTest {
    @Test
    fun foregroundHealthyAllowsFourAndEveryPressureLowersToOne() {
        val probe = FakeProbe()
        val gate = AndroidResourceGate(probe)
        assertEquals(4, gate.allowance())

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
        assertEquals(4, gate.allowance())
        probe.foreground = false
        assertEquals(1, gate.allowance())
        probe.foreground = true
        assertEquals(4, gate.allowance())
    }

    @Test fun cpuAllowanceNeverExceedsExistingPoolAndSamplesChanges() {
        val probe = FakeProbe()
        val gate = AndroidResourceGate(probe)
        for ((processors, expected) in listOf(0 to 1, 1 to 1, 2 to 2, 4 to 4, 8 to 4)) {
            probe.processors = processors
            assertEquals(expected, gate.allowance())
        }
    }

    private data class FakeProbe(
        var foreground: Boolean = true,
        var lowMemory: Boolean = false,
        var thermal: Boolean = false,
        var processors: Int = 4,
    ) : DeviceResourceProbe {
        override fun processorCount() = processors

        override fun isForeground() = foreground

        override fun isLowMemory() = lowMemory

        override fun isThermallyConstrained() = thermal
    }
}
