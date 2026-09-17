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
class LibsuRootAccessDeviceTest : RootDeviceTestHost() {
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
                verifyGrantedRootServiceAndLoss(rootAccess, killService = true)
            }

            "revoked" -> {
                verifyGrantedRootServiceAndLoss(rootAccess, killService = false)
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

    @Test
    fun d_repeatedServiceDeathAllowsOnlyExplicitRebind() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) == "granted")
        repeat(3) {
            val rootAccess = newAccess()
            assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
            verifyGrantedRootServiceAndLoss(rootAccess, killService = true)
            awaitCachedShellClosed()
            SystemClock.sleep(250)
            assertEquals(RootGrantState.LOST, rootAccess.status().grant)
            assertNull(rootAccess.rootServiceProcessIdForTest())
            rootAccess.disconnect()
        }
    }

    /**
     * HXA-094: after the controller closes a dead connection the OS state must follow the
     * in-memory state — the shell process and its process group are gone. The observation
     * shell below is an explicit request on the owner-approved policy for this test package,
     * never an automatic rebind of the dead connection.
     */
    @Test
    fun e_serviceDeathLeavesNoProcessOrProcessGroup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) == "granted")
        val rootAccess = newAccess()
        assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
        awaitStatus(rootAccess) { it.service == RootServiceState.CONNECTED }
        val deadPid = requireNotNull(rootAccess.rootServiceProcessIdForTest())
        // The test process's own SELinux domain cannot read the su domain's /proc entry, so
        // the process group is observed through the live root shell before the death.
        val processGroup =
            requireNotNull(
                Shell
                    .cmd("cat /proc/$deadPid/stat")
                    .exec()
                    .out
                    .joinToString(" ")
                    .substringAfterLast(')')
                    .trim()
                    .split(' ')
                    .getOrNull(2)
                    ?.toIntOrNull(),
            )

        assertTrue(Shell.cmd("kill -9 $deadPid").exec().isSuccess)
        val lost = awaitStatus(rootAccess) { it.grant == RootGrantState.LOST }
        assertEquals(RootServiceState.DISCONNECTED, lost.service)
        assertNull(rootAccess.rootServiceProcessIdForTest())
        awaitCachedShellClosed()

        val probe = Shell.cmd("test -d /proc/$deadPid; echo -n $?").exec()
        assertEquals("1", probe.out.joinToString("").trim())
        val groupMembers =
            Shell.cmd("ps -A -o PID,PGID | awk -v g=\"$processGroup\" 'NR > 1 && $2 == g'").exec()
        assertEquals(
            "process group $processGroup still has members after service death",
            "",
            groupMembers.out.joinToString("\n").trim(),
        )
        // Close the observation shell explicitly; the subject connection is already closed.
        Shell.getShell().close()
    }

    private fun verifyGrantedRootServiceAndLoss(
        rootAccess: LibsuRootAccess,
        killService: Boolean,
    ) {
        val granted = awaitStatus(rootAccess) { it.service == RootServiceState.CONNECTED }
        assertEquals(RootGrantState.GRANTED, granted.grant)
        val rootProcessId = requireNotNull(rootAccess.rootServiceProcessIdForTest())
        assertTrue(rootProcessId > 0)
        assertTrue(Shell.getCachedShell()?.isRoot == true)

        if (killService) {
            val kill = Shell.cmd("kill -9 $rootProcessId").exec()
            assertTrue(kill.isSuccess)
        } else {
            awaitOwnerRevocation()
            // Manager policy changes deny FUTURE requests; no portable passive revocation
            // signal exists for an already-open shell. The production background boundary
            // closes it, then a fresh explicit request must observe the changed policy.
            rootAccess.onAppBackgrounded()
        }
        val lost = awaitStatus(rootAccess) { it.grant == RootGrantState.LOST }
        assertEquals(RootServiceState.DISCONNECTED, lost.service)
        assertNull(rootAccess.rootServiceProcessIdForTest())
        if (!killService) {
            awaitCachedShellClosed()
            assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
            val denied = awaitTerminalStatus(rootAccess)
            assertEquals(RootGrantState.DENIED, denied.grant)
            assertEquals(RootServiceState.DISCONNECTED, denied.service)
        }
    }

    private fun awaitOwnerRevocation() {
        val files = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        val ready = files.resolve("hxa094-revoke-ready")
        val confirmed = files.resolve("hxa094-revoke-confirmed")
        confirmed.delete()
        ready.writeText(
            android.os.Process
                .myPid()
                .toString(),
        )
        try {
            val deadline = SystemClock.elapsedRealtime() + REVOCATION_TIMEOUT_MS
            while (!confirmed.exists() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            assertTrue("owner must revoke test package in manager, then confirm via host marker", confirmed.exists())
        } finally {
            ready.delete()
            confirmed.delete()
        }
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

        // Human-operated manager policy changes can cross chat turn boundaries.
        const val REVOCATION_TIMEOUT_MS = 1_800_000L
        const val WAIT_TIMEOUT_MS = 30_000L
        const val SHELL_CLOSE_TIMEOUT_MS = 5_000L
        const val SHELL_CLOSED_STABILITY_MS = 500L
        const val POLL_INTERVAL_MS = 50L
    }
}
