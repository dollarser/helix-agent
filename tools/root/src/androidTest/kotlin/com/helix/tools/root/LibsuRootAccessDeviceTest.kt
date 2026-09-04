package com.helix.tools.root

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.topjohnwu.superuser.Shell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LibsuRootAccessDeviceTest {
    private var access: LibsuRootAccess? = null

    @After
    fun tearDown() {
        access?.disconnect()
        access = null
        awaitCachedShellClosed()
    }

    @Test
    fun a_profileSwitchDoesNotConstructShellOrRequestRoot() {
        assertNull(Shell.getCachedShell())
        val rootAccess = newAccess()

        assertEquals(RootGrantState.UNAVAILABLE, rootAccess.status().grant)
        assertEquals(RootGrantState.UNAVAILABLE, rootAccess.onProfileChanged(true).grant)
        assertEquals(RootGrantState.UNAVAILABLE, rootAccess.onProfileChanged(false).grant)
        assertNull(Shell.getCachedShell())
    }

    @Test
    fun b_explicitRequestMatchesTheDeclaredDeviceProfile() {
        val rootAccess = newAccess()
        val expected =
            InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) ?: "rootless"

        assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
        when (expected) {
            "rootless",
            "denied",
            -> {
                val status = awaitTerminalStatus(rootAccess)
                assertEquals(RootGrantState.DENIED, status.grant)
                assertEquals(RootServiceState.DISCONNECTED, status.service)
                assertFalse(Shell.getCachedShell()?.isRoot ?: false)
            }

            "granted" -> {
                verifyGrantedRootServiceAndCrash(rootAccess)
            }

            else -> {
                error("unsupported $EXPECTED_ROOT_ARGUMENT=$expected")
            }
        }
    }

    @Test
    fun c_immediateDisconnectClosesARequestThatCompletesLate() {
        val expected =
            InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) ?: "rootless"
        assumeTrue("rooted grant UI is intentionally interactive", expected == "rootless")
        val rootAccess = newAccess()

        assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
        rootAccess.disconnect()
        assertEquals(RootGrantState.UNAVAILABLE, rootAccess.status().grant)
        awaitCachedShellClosed()
    }

    private fun verifyGrantedRootServiceAndCrash(rootAccess: LibsuRootAccess) {
        val granted = awaitStatus(rootAccess) { it.service == RootServiceState.CONNECTED }
        assertEquals(RootGrantState.GRANTED, granted.grant)
        val rootProcessId = requireNotNull(rootAccess.rootServiceProcessIdForTest())
        assertTrue(rootProcessId > 0)
        assertTrue(Shell.getCachedShell()?.isRoot == true)

        val kill = Shell.cmd("kill -9 $rootProcessId").exec()
        assertTrue(kill.isSuccess)
        val lost = awaitStatus(rootAccess) { it.grant == RootGrantState.LOST }
        assertEquals(RootServiceState.DISCONNECTED, lost.service)
        assertNull(rootAccess.rootServiceProcessIdForTest())
    }

    private fun awaitTerminalStatus(rootAccess: LibsuRootAccess): RootAccessStatus =
        awaitStatus(rootAccess) { it.grant != RootGrantState.REQUESTING }

    private fun awaitStatus(
        rootAccess: LibsuRootAccess,
        condition: (RootAccessStatus) -> Boolean,
    ): RootAccessStatus {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        var status = rootAccess.status()
        while (!condition(status) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            status = rootAccess.status()
        }
        assertTrue("timed out with $status", condition(status))
        return status
    }

    private fun newAccess(): LibsuRootAccess =
        LibsuRootAccess(ApplicationProvider.getApplicationContext()).also { access = it }

    private fun awaitCachedShellClosed() {
        val deadline = SystemClock.elapsedRealtime() + SHELL_CLOSE_TIMEOUT_MS
        var nullSince: Long? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Shell.getCachedShell() == null) {
                val observedSince = nullSince ?: SystemClock.elapsedRealtime().also { nullSince = it }
                if (SystemClock.elapsedRealtime() - observedSince >= SHELL_CLOSED_STABILITY_MS) return
            } else {
                nullSince = null
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertNull(Shell.getCachedShell())
    }

    private companion object {
        const val EXPECTED_ROOT_ARGUMENT = "hxa094ExpectedRoot"
        const val WAIT_TIMEOUT_MS = 30_000L
        const val SHELL_CLOSE_TIMEOUT_MS = 5_000L
        const val SHELL_CLOSED_STABILITY_MS = 500L
        const val POLL_INTERVAL_MS = 50L
    }
}
