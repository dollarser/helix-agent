package com.helix.runtime.proot.client

import com.helix.runtime.proot.ipc.UnavailableCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProotLocalStateCheckTest {
    private class FakeProbe(
        installed: Boolean = true,
        stopped: Boolean = false,
        enabled: Boolean = true,
        sameSigningSet: Boolean = true,
    ) : ProotRuntimeProbe {
        private val installedValue = installed
        private val stoppedValue = stopped
        private val enabledValue = enabled
        private val sameSigningSetValue = sameSigningSet

        override fun isInstalled(): Boolean = installedValue

        override fun isStopped(): Boolean = stoppedValue

        override fun isEnabled(): Boolean = enabledValue

        override fun isSameSigningSet(): Boolean = sameSigningSetValue

        override fun signingCertificateSha256s(): List<String> = if (sameSigningSetValue) listOf("aa") else listOf("bb")
    }

    @Test
    fun `healthy companion passes the local checks`() {
        assertNull(ProotLocalStateCheck(FakeProbe()).check())
    }

    @Test
    fun `uninstalled wins over every other state`() {
        val probe = FakeProbe(installed = false, stopped = true, enabled = false, sameSigningSet = false)
        assertEquals(UnavailableCause.NOT_INSTALLED, ProotLocalStateCheck(probe).check())
    }

    @Test
    fun `force stopped wins over disabled and signature`() {
        val probe = FakeProbe(stopped = true, enabled = false, sameSigningSet = false)
        assertEquals(UnavailableCause.PACKAGE_FORCED_STOPPED, ProotLocalStateCheck(probe).check())
    }

    @Test
    fun `disabled is reported when installed and running`() {
        val probe = FakeProbe(enabled = false, sameSigningSet = false)
        assertEquals(UnavailableCause.PACKAGE_DISABLED, ProotLocalStateCheck(probe).check())
    }

    @Test
    fun `signature mismatch is the last check`() {
        val probe = FakeProbe(sameSigningSet = false)
        assertEquals(UnavailableCause.SIGNATURE_MISMATCH, ProotLocalStateCheck(probe).check())
    }
}
