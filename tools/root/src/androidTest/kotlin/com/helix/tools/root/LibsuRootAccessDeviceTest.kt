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
import org.junit.Assert.assertSame
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
        access?.let(::assertDetached)
        access = null
        // This instrumentation owns the entire test process; production consumers must not do this.
        Shell.getCachedShell()?.close()
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
    fun c_immediateDisconnectIgnoresLateGrantWithoutClosingSharedShell() {
        val expected =
            InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) ?: "rootless"
        assumeTrue("rooted grant UI is intentionally interactive", expected == "rootless")
        val rootAccess = newAccess()

        assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
        rootAccess.disconnect()
        val sharedShell = Shell.getShell()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(RootGrantState.UNAVAILABLE, rootAccess.status().grant)
        assertDetached(rootAccess)
        assertSame(sharedShell, Shell.getCachedShell())
    }

    @Test
    fun d_repeatedServiceDeathAllowsOnlyExplicitRebind() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) == "granted")
        repeat(3) {
            val rootAccess = newAccess()
            assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
            verifyGrantedRootServiceAndLoss(rootAccess, killService = true)
            assertDetached(rootAccess)
            SystemClock.sleep(250)
            assertEquals(RootGrantState.LOST, rootAccess.status().grant)
            assertNull(rootAccess.rootServiceProcessIdForTest())
            rootAccess.disconnect()
        }
    }

    /**
     * The dead service and its descendants disappear, while the host's shared authorization
     * shell remains available to other consumers. Probing must not rebind the dead service.
     */
    @Test
    fun e_serviceDeathLeavesNoServiceProcessesAndRetainsHostShell() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) == "granted")
        val rootAccess = newAccess()
        assertEquals(RootRequestStatus.STARTED, rootAccess.requestRoot())
        awaitStatus(rootAccess) { it.service == RootServiceState.CONNECTED }
        val deadPid = requireNotNull(rootAccess.rootServiceProcessIdForTest())
        val sharedShell = Shell.getShell()
        // libsu may share its host shell's process group. Track the service's actual descendants,
        // not unrelated consumers or the observer's own ps/awk processes in that shared group.
        val processes = Shell.cmd("ps -A -o PID,PPID").exec()
        assertTrue(processes.isSuccess)
        val parentByPid =
            processes.out.drop(1).associate { line ->
                val fields = line.trim().split(Regex("\\s+"))
                fields[0].toInt() to fields[1].toInt()
            }
        val ownedPids = mutableSetOf(deadPid)
        while (ownedPids.addAll(parentByPid.filterValues { it in ownedPids }.keys)) { /* transitive descendants */ }

        assertTrue(Shell.cmd("kill -9 $deadPid").exec().isSuccess)
        val lost = awaitStatus(rootAccess) { it.grant == RootGrantState.LOST }
        assertEquals(RootServiceState.DISCONNECTED, lost.service)
        assertNull(rootAccess.rootServiceProcessIdForTest())
        assertDetached(rootAccess)

        ownedPids.forEach { pid ->
            val probe = Shell.cmd("test -d /proc/$pid; echo -n $?").exec()
            assertEquals("service-owned process $pid remains", "1", probe.out.joinToString("").trim())
        }
        assertSame(sharedShell, Shell.getCachedShell())
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
            // Manager policy changes deny FUTURE requests. Backgrounding detaches this
            // consumer, without closing the host's shared authorization shell.
            rootAccess.onAppBackgrounded()
        }
        val lost = awaitStatus(rootAccess) { it.grant == RootGrantState.LOST }
        assertEquals(RootServiceState.DISCONNECTED, lost.service)
        assertNull(rootAccess.rootServiceProcessIdForTest())
        if (!killService) {
            assertDetached(rootAccess)
            // Explicit test-owned fresh-grant probe, not a production consumer disconnect.
            Shell.getCachedShell()?.close()
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

    private fun assertDetached(rootAccess: LibsuRootAccess) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(RootServiceState.DISCONNECTED, rootAccess.status().service)
        assertNull(rootAccess.connectedBinder())
        assertNull(rootAccess.rootServiceProcessIdForTest())
        assertEquals(
            RootOperationResult.Failed("ROOT_NOT_CONNECTED"),
            rootAccess.execute(RootOperationRequest.ProcessList(1)),
        )
        SystemClock.sleep(DISCONNECTED_STABILITY_MS)
        assertEquals(RootServiceState.DISCONNECTED, rootAccess.status().service)
        assertNull(rootAccess.connectedBinder())
    }

    private companion object {
        const val EXPECTED_ROOT_ARGUMENT = "hxa094ExpectedRoot"

        // Human-operated manager policy changes can cross chat turn boundaries.
        const val REVOCATION_TIMEOUT_MS = 1_800_000L
        const val WAIT_TIMEOUT_MS = 30_000L
        const val DISCONNECTED_STABILITY_MS = 500L
        const val POLL_INTERVAL_MS = 50L
    }
}
