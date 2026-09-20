package com.helix.app.proot

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.app.ui.resetDeterministicUiState
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties

/** Requires actual device reboot between setup and verification, never a changed setting fixture. */
class DetachedJobRebootDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val marker get() = File(app.noBackupFilesDir, "detached-reboot.properties")

    @Test fun rebootAllowsOriginalOrphanSettlementWithoutOutputOrReplay() =
        runBlocking {
            val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
            assumeTrue("Requires owned emulator reboot runner", phase != null)
            require(phase in setOf("setup", "verify"))
            if (phase == "setup") setup() else verify()
        }

    private suspend fun setup() {
        compose.resetDeterministicUiState()
        ScriptedTaskModelServer().use { server ->
            server.start()
            val f = DetachedGoalFixture(app, server)
            f.prepare()
            compose.waitUntil(10_000) { f.container.chatService.screen.value.openSessionId == f.session }
            f.submit(collectInModel = false, script = "sleep 60; printf MUST_NOT_REPLAY > result.txt")
            compose.waitUntil(15_000) {
                f.storage.turns
                    .resolve(f.turn)
                    .state == "COMPLETED"
            }
            val job = DetachedJobDashboard.read(f.storage).single { it.sessionId == f.session }
            val binding = ProotJobBindingStore(f.storage).resolveDetached(f.session, job.callId)
            val client = DetachedJobClient(app)
            compose.waitUntil(5_000) { client.query(binding).record?.state == ProotJobState.RUNNING }
            repeat(2) {
                assertFalse(DetachedJobBootProof.canSettle(f.storage, binding, null, DetachedJobBootProof.current(app)))
            }
            val facts =
                Properties().apply {
                    setProperty("session", f.session)
                    setProperty("goal", f.goal)
                    setProperty("turn", f.turn)
                    setProperty("call", job.callId)
                    setProperty("output", f.output.name)
                    setProperty("boot", requireNotNull(DetachedJobBootProof.current(app)).toString())
                }
            marker.outputStream().use {
                facts.store(it, "Synthetic reboot fixture")
                it.fd.sync()
            }
            File(app.noBackupFilesDir, "recovery-device-pid").outputStream().use {
                it.write(Process.myPid().toString().toByteArray())
                it.fd.sync()
            }
            Process.killProcess(Process.myPid())
            error("Setup must terminate its own process")
        }
    }

    private fun verify() {
        val facts = Properties().apply { marker.inputStream().use { load(it) } }
        assertTrue(requireNotNull(DetachedJobBootProof.current(app)) > facts.getProperty("boot").toInt())
        val storage = app.appContainer.storage
        val session = facts.getProperty("session")
        val binding = ProotJobBindingStore(storage).resolveDetached(session, facts.getProperty("call"))
        assertTrue(DetachedJobBootProof.canSettle(storage, binding, null, DetachedJobBootProof.current(app)))
        val client = DetachedJobClient(app)
        compose.waitUntil(15_000) { client.query(binding).record?.state == ProotJobState.ORPHANED }
        val run = storage.goalRuns.listByGoal(facts.getProperty("goal")).single()
        val lease = requireNotNull(storage.goalUsageReservations.byId("proot-lease-${binding.executionId}"))
        assertEquals("INTERRUPTED", lease.state)
        assertNotNull(run.endedAt)
        val job = DetachedJobDashboard.read(storage).single { it.callId == binding.toolCallId }
        assertTrue(job.settlementPending)
        assertNotNull(ExecutionOwnershipStore(File(app.filesDir, "execution-admission/owner")).read())
        repeat(2) { collect(job) }
        assertNull(ExecutionOwnershipStore(File(app.filesDir, "execution-admission/owner")).read())
        assertEquals(run, storage.goalRuns.resolve(run.id))
        assertEquals(lease, storage.goalUsageReservations.byId(lease.id))
        assertEquals(1, storage.turns.listBySession(session).size)
        assertEquals(2, storage.toolCalls.listByTurn(facts.getProperty("turn")).size)
        assertFalse(File(app.filesDir, "workspaces/app/output/${facts.getProperty("output")}").exists())
        assertFalse(DetachedJobDashboard.read(storage).single { it.callId == job.callId }.settlementPending)
        assertEquals(ProotJobState.ORPHANED, client.query(binding).record?.state)
        marker.delete()
    }

    private fun collect(job: BackgroundJobUi) {
        val chat = app.appContainer.chatService
        chat.performBackgroundJobAction(job, BackgroundJobAction.COLLECT)
        compose.waitUntil(30_000) {
            val action = chat.backgroundJobAction.value
            action?.callId == job.callId && !action.busy
        }
        assertEquals(BackgroundJobActionOutcome.SETTLED, chat.backgroundJobAction.value?.outcome)
    }
}
