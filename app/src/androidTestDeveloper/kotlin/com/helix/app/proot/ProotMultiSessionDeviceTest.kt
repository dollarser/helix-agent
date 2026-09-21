package com.helix.app.proot

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.terminal.ManualTerminal
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Multi-session concurrency, capacity limits, observer isolation, and settlement promotion. */
class ProotMultiSessionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as HelixApplication).appContainer

    @Test
    fun dualSessionsRunConcurrentlyAndIsolateWorkspaces() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val rel1 = "multi-test-ws1-${UUID.randomUUID()}"
                val rel2 = "multi-test-ws2-${UUID.randomUUID()}"
                val dir1 = File(context.filesDir, "workspaces/app/$rel1").apply { check(mkdirs()) }
                val dir2 = File(context.filesDir, "workspaces/app/$rel2").apply { check(mkdirs()) }
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    val s1 = terminal.start(rel1, 30_000)
                    val s2 = terminal.start(rel2, 30_000)

                    val activeSessions = terminal.sessions()
                    assertEquals(2, activeSessions.size)
                    assertTrue(activeSessions.any { it.sessionId == s1.sessionId })
                    assertTrue(activeSessions.any { it.sessionId == s2.sessionId })

                    val conn1 = terminal.attach(s1.sessionId)
                    val conn2 = terminal.attach(s2.sessionId)
                    try {
                        awaitText(conn1, "helix> ")
                        awaitText(conn2, "helix> ")

                        assertEquals("ACCEPTED", conn1.write("printf s1_content > out1.txt\n".toByteArray()))
                        assertEquals("ACCEPTED", conn2.write("printf s2_content > out2.txt\n".toByteArray()))

                        withTimeout(10_000) { while (!File(dir1, "out1.txt").exists()) delay(25) }
                        withTimeout(10_000) { while (!File(dir2, "out2.txt").exists()) delay(25) }

                        assertEquals("s1_content", File(dir1, "out1.txt").readText())
                        assertEquals("s2_content", File(dir2, "out2.txt").readText())

                        assertFalse("Workspace 1 must not contain session 2 file", File(dir1, "out2.txt").exists())
                        assertFalse("Workspace 2 must not contain session 1 file", File(dir2, "out1.txt").exists())
                    } finally {
                        conn1.detach()
                        conn2.detach()
                    }

                    terminal.stop(s1.sessionId)
                    terminal.stop(s2.sessionId)
                    awaitStopped(terminal, s1.sessionId)
                    awaitStopped(terminal, s2.sessionId)
                    terminal.settle(s1.sessionId)
                    terminal.settle(s2.sessionId)
                    assertFalse(terminal.hasSession())
                } finally {
                    cleanRemainingSessions(terminal)
                    container.profileStore.switchTo(previous)
                    dir1.deleteRecursively()
                    dir2.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun thirdSessionCreationRejectedWhenCapacityExhausted() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val rel1 = "multi-cap-1-${UUID.randomUUID()}"
                val rel2 = "multi-cap-2-${UUID.randomUUID()}"
                val rel3 = "multi-cap-3-${UUID.randomUUID()}"
                val dir1 = File(context.filesDir, "workspaces/app/$rel1").apply { check(mkdirs()) }
                val dir2 = File(context.filesDir, "workspaces/app/$rel2").apply { check(mkdirs()) }
                val dir3 = File(context.filesDir, "workspaces/app/$rel3").apply { check(mkdirs()) }
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    val s1 = terminal.start(rel1, 30_000)
                    val s2 = terminal.start(rel2, 30_000)
                    assertEquals(2, terminal.sessions().size)

                    // 3rd session creation must fail
                    assertTrue(runCatching { terminal.start(rel3, 30_000) }.isFailure)

                    // Existing 2 sessions remain running and intact
                    assertEquals(2, terminal.sessions().size)
                    assertEquals("RUNNING", terminal.query(s1.sessionId).phase)
                    assertEquals("RUNNING", terminal.query(s2.sessionId).phase)

                    terminal.stop(s1.sessionId)
                    terminal.stop(s2.sessionId)
                    awaitStopped(terminal, s1.sessionId)
                    awaitStopped(terminal, s2.sessionId)
                    terminal.settle(s1.sessionId)
                    terminal.settle(s2.sessionId)
                    assertFalse(terminal.hasSession())
                } finally {
                    cleanRemainingSessions(terminal)
                    container.profileStore.switchTo(previous)
                    dir1.deleteRecursively()
                    dir2.deleteRecursively()
                    dir3.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun singleWriterAndObserverModeEnforced() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val rel = "multi-obs-${UUID.randomUUID()}"
                val dir = File(context.filesDir, "workspaces/app/$rel").apply { check(mkdirs()) }
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    val session = terminal.start(rel, 30_000)

                    val writerConn = terminal.attach(session.sessionId)
                    assertTrue("First attach must be writer", writerConn.isWriter)

                    val observerConn = terminal.attach(session.sessionId)
                    assertFalse("Second attach while writer active must be observer", observerConn.isWriter)

                    // Observer cannot write
                    assertThrows(IllegalStateException::class.java) {
                        runBlocking { observerConn.write("touch denied.txt\n".toByteArray()) }
                    }

                    // Writer can write
                    assertEquals("ACCEPTED", writerConn.write("printf writer_ok > ok.txt\n".toByteArray()))
                    withTimeout(10_000) { while (!File(dir, "ok.txt").exists()) delay(25) }
                    assertEquals("writer_ok", File(dir, "ok.txt").readText())

                    // Detach writer -> subsequent attach can become writer
                    writerConn.detach()

                    val secondWriter = terminal.attach(session.sessionId)
                    assertTrue("Subsequent attach after writer detach must become writer", secondWriter.isWriter)

                    secondWriter.detach()
                    observerConn.detach()

                    terminal.stop(session.sessionId)
                    awaitStopped(terminal, session.sessionId)
                    terminal.settle(session.sessionId)
                    assertFalse(terminal.hasSession())
                } finally {
                    cleanRemainingSessions(terminal)
                    container.profileStore.switchTo(previous)
                    dir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun settlingFirstSessionPromotesSurvivingSessionAndPreservesAdmission() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val rel1 = "multi-settle-1-${UUID.randomUUID()}"
                val rel2 = "multi-settle-2-${UUID.randomUUID()}"
                val dir1 = File(context.filesDir, "workspaces/app/$rel1").apply { check(mkdirs()) }
                val dir2 = File(context.filesDir, "workspaces/app/$rel2").apply { check(mkdirs()) }
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    val s1 = terminal.start(rel1, 30_000)
                    val s2 = terminal.start(rel2, 30_000)
                    verifyRetainedAdmission()

                    // Stop and settle session 1
                    terminal.stop(s1.sessionId)
                    awaitStopped(terminal, s1.sessionId)
                    terminal.settle(s1.sessionId)

                    // Session 2 is still running and alive!
                    assertTrue(terminal.hasSession())
                    val surviving = terminal.sessions()
                    assertEquals(1, surviving.size)
                    assertEquals(s2.sessionId, surviving.first().sessionId)
                    assertEquals("RUNNING", surviving.first().phase)

                    // Admission must still be retained for session 2
                    verifyRetainedAdmission()

                    // Session 2 can execute commands normally
                    val conn2 = terminal.attach(s2.sessionId)
                    try {
                        assertEquals("ACCEPTED", conn2.write("printf surviving > alive.txt\n".toByteArray()))
                        withTimeout(10_000) { while (!File(dir2, "alive.txt").exists()) delay(25) }
                        assertEquals("surviving", File(dir2, "alive.txt").readText())
                    } finally {
                        conn2.detach()
                    }

                    // Settle session 2
                    terminal.stop(s2.sessionId)
                    awaitStopped(terminal, s2.sessionId)
                    terminal.settle(s2.sessionId)
                    assertFalse(terminal.hasSession())
                } finally {
                    cleanRemainingSessions(terminal)
                    container.profileStore.switchTo(previous)
                    dir1.deleteRecursively()
                    dir2.deleteRecursively()
                }
            }
        }
    }

    private suspend fun cleanRemainingSessions(terminal: ManualTerminal) {
        for (session in terminal.sessions()) {
            runCatching { terminal.stop(session.sessionId) }
            runCatching { awaitStopped(terminal, session.sessionId) }
            runCatching { terminal.settle(session.sessionId) }
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

    private suspend fun awaitStopped(
        terminal: ManualTerminal,
        sessionId: String,
    ): ManualTerminal.State =
        withTimeout(10_000) {
            var state = terminal.query(sessionId)
            while (!state.canSettle) {
                check(state.phase != "UNKNOWN") { "Unexpected unknown terminal session: $sessionId" }
                delay(25)
                state = terminal.query(sessionId)
            }
            state
        }
}
