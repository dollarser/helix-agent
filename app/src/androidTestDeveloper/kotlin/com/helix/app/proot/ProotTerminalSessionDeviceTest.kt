package com.helix.app.proot

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.terminal.ManualTerminal
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Product facade -> private foreground service -> actual PRoot. Renderer acceptance remains separate. */
class ProotTerminalSessionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as HelixApplication).appContainer

    @Test
    fun manualSessionMapsRealWorkspaceReconnectsAndSettlesOriginalOwner() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val relative = "terminal-test-${UUID.randomUUID()}"
                val directory = File(context.filesDir, "workspaces/app/$relative").apply { check(mkdirs()) }
                try {
                    container.profileStore.switchTo(SafetyProfile.STANDARD)
                    assertTrue(runCatching { terminal.start(relative) }.isFailure)
                    assertFalse(terminal.hasSession())
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    terminal.start(relative, 30_000)
                    assertTrue(terminal.hasSession())
                    verifyRetainedAdmission()
                    assertTrue(runCatching { terminal.start(relative) }.isFailure)
                    assertTrue(runCatching { terminal.settle() }.isFailure)
                    exerciseConnections(terminal, directory)
                    terminal.stop()
                    val stopped = awaitStopped(terminal)
                    assertEquals("USER", stopped.stopReason)
                    assertTrue(stopped.canSettle)
                    terminal.settle()
                    assertFalse(terminal.hasSession())
                } finally {
                    if (terminal.hasSession()) {
                        terminal.stop()
                        awaitStopped(terminal)
                        terminal.settle()
                    }
                    container.profileStore.switchTo(previous)
                    directory.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun manualLeaseExpiresWithoutGoalBudgetAndReleasesOnlyAfterSettlement() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    terminal.start(leaseMs = 1500)
                    val stopped = awaitStopped(terminal)
                    assertEquals("LEASE_EXPIRED", stopped.stopReason)
                    assertTrue(terminal.hasSession())
                    terminal.settle()
                    assertFalse(terminal.hasSession())
                } finally {
                    if (terminal.hasSession()) {
                        terminal.stop()
                        awaitStopped(terminal)
                        terminal.settle()
                    }
                    container.profileStore.switchTo(previous)
                }
            }
        }
    }

    private suspend fun exerciseConnections(
        terminal: ManualTerminal,
        directory: File,
    ) {
        val first = terminal.attach()
        try {
            awaitText(first, "helix> ")
            assertTrue(runCatching { terminal.attach() }.isFailure)
            assertEquals(
                "ACCEPTED",
                first.write("export KEEP=present; printf real > bound.txt\n".toByteArray()),
            )
            withTimeout(10_000) { while (!File(directory, "bound.txt").exists()) delay(25) }
            assertEquals("real", File(directory, "bound.txt").readText())
        } finally {
            coroutineScope {
                launch {
                    currentCoroutineContext().cancel()
                    first.detach()
                }.join()
            }
        }
        val second = terminal.attach()
        try {
            second.resize(33, 99)
            assertEquals(
                "ACCEPTED",
                second.write("printf 'STATE_%s\\n' \"\$KEEP\"; stty size\n".toByteArray()),
            )
            awaitText(second, "STATE_present")
            awaitText(second, "33 99")
        } finally {
            second.detach()
        }
    }

    private fun verifyRetainedAdmission() {
        val store = ExecutionOwnershipStore(File(context.filesDir, "execution-admission/owner"))
        checkNotNull(store.read())
        val competing =
            com.helix.tools.framework
                .ExecutionOwnership(store)
        check(competing.acquire("competing-local-write") == null)
    }

    private suspend fun awaitText(
        connection: ManualTerminal.Connection,
        expected: String,
    ) {
        withTimeout(10_000) {
            while (!connection
                    .read(null)
                    .bytes
                    .toString(Charsets.UTF_8)
                    .contains(expected)
            ) {
                delay(25)
            }
        }
    }

    private suspend fun awaitStopped(terminal: ManualTerminal): ManualTerminal.State =
        withTimeout(10_000) {
            var state = terminal.query()
            while (!state.canSettle) {
                check(state.phase != "UNKNOWN") { "Unexpected unknown terminal" }
                delay(25)
                state = terminal.query()
            }
            state
        }
}
