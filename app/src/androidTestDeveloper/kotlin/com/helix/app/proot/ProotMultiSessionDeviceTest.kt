package com.helix.app.proot

import android.content.Context
import android.os.Parcel
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.terminal.ManualTerminal
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.client.ProotConnection
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.core.PtySessionStore
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
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
                    verifyRetainedAdmission(context)

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
                    verifyRetainedAdmission(context)

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

    @Test
    fun agentJobMutualExclusionAndAdmissionRelease() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val rel = "multi-agent-${UUID.randomUUID()}"
                val dir = File(context.filesDir, "workspaces/app/$rel").apply { check(mkdirs()) }
                val ownership = checkNotNull(container.executionOwnership)
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    val session = terminal.start(rel, 30_000)

                    // 1. Manual terminal holds admission -> competing agent acquire must be rejected
                    org.junit.Assert.assertNull(
                        "Agent cannot acquire execution while terminal is active",
                        ownership.acquire("agent-call"),
                    )

                    // 2. Stop and settle manual terminal -> admission is released
                    terminal.stop(session.sessionId)
                    awaitStopped(terminal, session.sessionId)
                    terminal.settle(session.sessionId)
                    assertFalse(terminal.hasSession())

                    // 3. Agent acquires exclusive permit -> terminal start must be refused
                    val agentPermit = checkNotNull(ownership.acquire("agent-call"))
                    try {
                        assertTrue(
                            "Terminal start must fail while agent holds admission",
                            runCatching { terminal.start(rel, 30_000) }.isFailure,
                        )
                    } finally {
                        // 4. Release/cancel agent permit -> terminal can start again
                        agentPermit.close()
                    }

                    val nextSession = terminal.start(rel, 30_000)
                    assertTrue(terminal.hasSession())
                    terminal.stop(nextSession.sessionId)
                    awaitStopped(terminal, nextSession.sessionId)
                    terminal.settle(nextSession.sessionId)
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
    fun twentyCycleLifecycleSoak() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val rel = "multi-soak-${UUID.randomUUID()}"
                val dir = File(context.filesDir, "workspaces/app/$rel").apply { check(mkdirs()) }
                val observedPids = mutableListOf<Int>()
                val initialFds = File("/proc/self/fd").listFiles()?.size ?: -1
                val initialThreads = File("/proc/self/task").listFiles()?.size ?: Thread.activeCount()
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    repeat(20) { iteration ->
                        MultiSessionTestSupport.runSoakCycle(terminal, rel, dir, iteration, observedPids)
                    }
                    MultiSessionTestSupport.assertSoakResources(observedPids, initialFds, initialThreads)
                } finally {
                    cleanRemainingSessions(terminal)
                    container.profileStore.switchTo(previous)
                    dir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun realProcessDeathRecoveryAcrossMainRecreationAndRuntimeKill() {
        ensureInstalledRuntime(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking {
                val previous = container.profileStore.profile
                val terminal = checkNotNull(container.manualTerminal)
                val rel1 = "multi-crash-1-${UUID.randomUUID()}"
                val rel2 = "multi-crash-2-${UUID.randomUUID()}"
                val dir1 = File(context.filesDir, "workspaces/app/$rel1").apply { check(mkdirs()) }
                val dir2 = File(context.filesDir, "workspaces/app/$rel2").apply { check(mkdirs()) }
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    val s1 = terminal.start(rel1, 30_000)
                    val s2 = terminal.start(rel2, 30_000)
                    assertEquals(2, terminal.sessions().size)

                    // 1. Recreate MainActivity: verify surviving sessions and execution
                    scenario.recreate()
                    val recovered = checkNotNull(container.manualTerminal)
                    MultiSessionTestSupport.verifySurvivingSessions(recovered, s1.sessionId, s2.sessionId, dir1, dir2)

                    // 2. Kill the :proot Runtime process and verify UNKNOWN same-boot status
                    MultiSessionTestSupport.killRuntime(context)
                    MultiSessionTestSupport.awaitRuntimeLoss(recovered)
                    verifyRetainedAdmission(context)

                    // 3. Reconcile simulated reboot proof and settle
                    MultiSessionTestSupport.reconcileRebootAndSettle(recovered, context, s1.sessionId, s2.sessionId)

                    // 4. Verify fresh session start succeeds after recovery
                    MultiSessionTestSupport.verifyCleanSessionStart(recovered, context)
                } finally {
                    cleanRemainingSessions(terminal)
                    container.profileStore.switchTo(previous)
                    dir1.deleteRecursively()
                    dir2.deleteRecursively()
                }
            }
        }
    }
}

private object MultiSessionTestSupport {
    suspend fun runSoakCycle(
        terminal: ManualTerminal,
        rel: String,
        dir: File,
        iteration: Int,
        observedPids: MutableList<Int>,
    ) {
        val session = terminal.start(rel, 30_000)
        assertTrue("Iteration $iteration must have session", terminal.hasSession())
        val conn = terminal.attach(session.sessionId)
        try {
            assertEquals(
                "ACCEPTED",
                conn.write("echo \$\$ > pid.txt; printf cycle$iteration > out.txt\n".toByteArray()),
            )
            val pidFile = File(dir, "pid.txt")
            val outFile = File(dir, "out.txt")
            withTimeout(10_000) { while (!pidFile.exists() || !outFile.exists()) delay(20) }
            val pid = pidFile.readText().trim().toInt()
            assertTrue("Iteration $iteration shell PID must be valid", pid > 1)
            assertTrue("Iteration $iteration shell PID must exist while active", File("/proc/$pid").exists())
            observedPids.add(pid)
            pidFile.delete()
            outFile.delete()
        } finally {
            conn.detach()
        }
        terminal.stop(session.sessionId)
        awaitStopped(terminal, session.sessionId)
        terminal.settle(session.sessionId)
        assertFalse("Iteration $iteration must be settled", terminal.hasSession())
        val lastPid = observedPids.last()
        withTimeout(5000) { while (File("/proc/$lastPid").exists()) delay(25) }
        assertFalse("Child shell PID $lastPid must exit after settle", File("/proc/$lastPid").exists())
    }

    fun assertSoakResources(
        observedPids: List<Int>,
        initialFds: Int,
        initialThreads: Int,
    ) {
        assertEquals(20, observedPids.size)
        for (pid in observedPids) {
            assertFalse("Child shell PID $pid must not survive 20-cycle soak", File("/proc/$pid").exists())
        }
        val finalFds = File("/proc/self/fd").listFiles()?.size ?: -1
        val finalThreads = File("/proc/self/task").listFiles()?.size ?: Thread.activeCount()
        if (initialFds > 0 && finalFds > 0) {
            val fdDelta = finalFds - initialFds
            assertTrue(
                "FD delta must be bounded (initial: $initialFds, final: $finalFds)",
                kotlin.math.abs(fdDelta) <= 15,
            )
        }
        if (initialThreads > 0 && finalThreads > 0) {
            val threadDelta = finalThreads - initialThreads
            assertTrue(
                "Thread delta must be bounded (initial: $initialThreads, final: $finalThreads)",
                kotlin.math.abs(threadDelta) <= 10,
            )
        }
    }

    suspend fun verifySurvivingSessions(
        terminal: ManualTerminal,
        s1Id: String,
        s2Id: String,
        dir1: File,
        dir2: File,
    ) {
        val survivingSessions = terminal.sessions()
        assertEquals(2, survivingSessions.size)
        assertTrue("Surviving sessions must remain running", survivingSessions.all { it.phase == "RUNNING" })

        val conn1 = terminal.attach(s1Id)
        try {
            assertEquals("ACCEPTED", conn1.write("printf live1 > survive1.txt\n".toByteArray()))
            withTimeout(10_000) { while (!File(dir1, "survive1.txt").exists()) delay(20) }
            assertEquals("live1", File(dir1, "survive1.txt").readText())
        } finally {
            conn1.detach()
        }
        val conn2 = terminal.attach(s2Id)
        try {
            assertEquals("ACCEPTED", conn2.write("printf live2 > survive2.txt\n".toByteArray()))
            withTimeout(10_000) { while (!File(dir2, "survive2.txt").exists()) delay(20) }
            assertEquals("live2", File(dir2, "survive2.txt").readText())
        } finally {
            conn2.detach()
        }
    }

    suspend fun awaitRuntimeLoss(terminal: ManualTerminal) {
        withTimeout(15_000) {
            while (true) {
                val states = runCatching { terminal.sessions() }.getOrNull()
                if (states != null && states.size == 2 && states.all { it.phase == "UNKNOWN" }) {
                    break
                }
                delay(50)
            }
        }
        val lostSessions = terminal.sessions()
        assertEquals(2, lostSessions.size)
        for (lost in lostSessions) {
            assertEquals("UNKNOWN", lost.phase)
            assertEquals("RUNTIME_LOST", lost.stopReason)
            assertFalse("Same-boot execution without reboot proof must not settle", lost.canSettle)
        }
    }

    suspend fun reconcileRebootAndSettle(
        terminal: ManualTerminal,
        context: Context,
        s1Id: String,
        s2Id: String,
    ) {
        val sessionStore = PtySessionStore(File(context.filesDir, "terminal-sessions"))
        for (id in listOf(s1Id, s2Id)) {
            val record = checkNotNull(sessionStore.read(id))
            val currentBoot = (record.origin.bootCount ?: 0) + 1
            val rebooted = record.afterReboot(currentBoot)
            check(sessionStore.compareAndSet(record, rebooted))
        }
        val rebootedSessions = terminal.sessions()
        assertTrue("Rebooted session 1 must be settleable", rebootedSessions.first { it.sessionId == s1Id }.canSettle)
        assertTrue("Rebooted session 2 must be settleable", rebootedSessions.first { it.sessionId == s2Id }.canSettle)

        terminal.settle(s1Id)
        terminal.settle(s2Id)
        assertFalse(terminal.hasSession())

        val admissionStore = ExecutionOwnershipStore(File(context.filesDir, "execution-admission/owner"))
        org.junit.Assert.assertNull(
            "Host admission must be released after settling all sessions",
            admissionStore.read(),
        )
    }

    suspend fun verifyCleanSessionStart(
        terminal: ManualTerminal,
        context: Context,
    ) {
        val rel = "multi-fresh-${UUID.randomUUID()}"
        val dir = File(context.filesDir, "workspaces/app/$rel").apply { check(mkdirs()) }
        try {
            val session = terminal.start(rel, 30_000)
            assertTrue(terminal.hasSession())
            terminal.stop(session.sessionId)
            awaitStopped(terminal, session.sessionId)
            terminal.settle(session.sessionId)
            assertFalse(terminal.hasSession())
        } finally {
            dir.deleteRecursively()
        }
    }

    fun killRuntime(context: Context) {
        val supervisor = ProotRuntimeSupervisor(context)
        val connection = supervisor.openConnection() as ProotConnection.Opened
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            runCatching { connection.binder.transact(ProotRuntimeProtocol.TX_DEBUG_SELF_KILL, data, reply, 0) }
        } finally {
            data.recycle()
            reply.recycle()
            supervisor.closeConnection()
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

private fun verifyRetainedAdmission(context: android.content.Context) {
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
