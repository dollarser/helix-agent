package com.helix.app.proot

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.client.DetachedJobClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties

/** Host runner executes setup (real process death) then verify in a newly created process. */
class DetachedGoalRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val marker get() = File(app.noBackupFilesDir, "detached-goal-recovery.properties")

    @Test fun originalTerminalResultSurvivesHostDeathWithoutReplayOrDoubleCharge() =
        runBlocking {
            val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
            assumeTrue("Requires the owned two-phase recovery runner", phase != null)
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
            f.submit(collectInModel = false)
            compose.waitUntil(30_000) {
                f.storage.turns
                    .resolve(f.turn)
                    .state == "COMPLETED"
            }
            f.originalResult()
            val job = DetachedJobDashboard.read(f.storage).single { it.sessionId == f.session }
            val binding = ProotJobBindingStore(f.storage).resolveDetached(f.session, job.callId)
            val record = requireNotNull(DetachedJobClient(app).query(binding).record)
            val facts =
                Properties().apply {
                    setProperty("session", f.session)
                    setProperty("goal", f.goal)
                    setProperty("turn", f.turn)
                    setProperty("call", job.callId)
                    setProperty("lease", requireNotNull(f.leaseId))
                    setProperty("output", f.output.name)
                    setProperty("commit", requireNotNull(record.terminalCommit))
                }
            marker.outputStream().use {
                facts.store(it, "Synthetic recovery fixture")
                it.fd.sync()
            }
            File(app.noBackupFilesDir, "recovery-device-pid").outputStream().use {
                it.write(Process.myPid().toString().toByteArray())
                it.fd.sync()
            }
            assertFalse(f.output.exists())
            Process.killProcess(Process.myPid())
            error("Setup must terminate the original process")
        }
    }

    private suspend fun verify() {
        val oldPid = File(app.noBackupFilesDir, "recovery-device-pid").readText().toInt()
        assertNotEquals(oldPid, Process.myPid())
        val facts = Properties().apply { marker.inputStream().use { load(it) } }
        val container = app.appContainer
        val storage = container.storage
        val session = facts.getProperty("session")
        val goal = facts.getProperty("goal")
        val run = storage.goalRuns.listByGoal(goal).single()
        val lease = requireNotNull(storage.goalUsageReservations.byId(facts.getProperty("lease")))
        assertNotNull(run.endedAt)
        assertEquals("INTERRUPTED", lease.state)
        assertEquals("PAUSED", storage.goals.resolve(goal).state)
        assertNotNull(ExecutionOwnershipStore(File(app.filesDir, "execution-admission/owner")).read())
        val binding = ProotJobBindingStore(storage).resolveDetached(session, facts.getProperty("call"))
        val record = requireNotNull(DetachedJobClient(app).query(binding).record)
        assertEquals(facts.getProperty("commit"), record.terminalCommit)
        val job = DetachedJobDashboard.read(storage).single { it.callId == binding.toolCallId }
        assertTrue(job.settlementPending)
        repeat(2) { collect(job) }
        assertEquals(run, storage.goalRuns.resolve(run.id))
        assertEquals(lease, storage.goalUsageReservations.byId(lease.id))
        assertEquals(1, storage.turns.listBySession(session).size)
        assertEquals(2, storage.toolCalls.listByTurn(facts.getProperty("turn")).size)
        val output = File(app.filesDir, "workspaces/app/output/${facts.getProperty("output")}")
        assertEquals("goal-result", output.readText())
        assertFalse(DetachedJobDashboard.read(storage).single { it.callId == job.callId }.settlementPending)
        storage.sessions
            .resolve(session)
            .providerId
            ?.let { container.providerService.delete(it) }
        storage.sessions.archive(session, System.currentTimeMillis())
        container.profileStore.switchTo(SafetyProfile.STANDARD)
        output.delete()
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
