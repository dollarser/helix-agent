package com.helix.app.proot

import android.os.Parcel
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.app.ui.resetDeterministicUiState
import com.helix.runtime.proot.client.DetachedJobClient
import com.helix.runtime.proot.client.ProotConnection
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.ipc.ProotJobState
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class DetachedGoalRuntimeDeathDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun orphanConsumesOriginalLeaseOnceWithoutImportReplayOrOwnershipRelease() =
        runBlocking {
            compose.resetDeterministicUiState()
            ScriptedTaskModelServer().use { server ->
                server.start()
                val f = DetachedGoalFixture(app, server)
                try {
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
                    killRuntime()
                    compose.waitUntil(15_000) { client.query(binding).record?.state == ProotJobState.ORPHANED }
                    assertUnknownSettlement(f, job)
                } finally {
                    f.close()
                }
            }
        }

    private fun assertUnknownSettlement(
        f: DetachedGoalFixture,
        job: BackgroundJobUi,
    ) {
        val run =
            f.storage.goalRuns
                .listByGoal(f.goal)
                .single()
        val lease =
            f.storage.goalUsageReservations
                .pendingForRun(run.id)
                .single { it.kind == "TIME_LEASE" }
        action(job, BackgroundJobAction.QUERY)
        action(job, BackgroundJobAction.CANCEL)
        action(job, BackgroundJobAction.COLLECT)
        val settled = requireNotNull(f.storage.goalUsageReservations.byId(lease.id))
        assertEquals("INTERRUPTED", settled.state)
        assertEquals(lease.reservedMillis + (lease.chargedMillis ?: 0), settled.chargedMillis)
        assertEquals(
            "PAUSED",
            f.storage.goals
                .resolve(f.goal)
                .state,
        )
        val ended = f.storage.goalRuns.resolve(run.id)
        assertNotNull(ended.endedAt)
        action(job, BackgroundJobAction.COLLECT)
        assertEquals(settled, f.storage.goalUsageReservations.byId(lease.id))
        assertEquals(ended, f.storage.goalRuns.resolve(run.id))
        assertNotNull(ExecutionOwnershipStore(File(app.filesDir, "execution-admission/owner")).read())
        assertFalse(f.output.exists())
        assertEquals(
            1,
            f.storage.turns
                .listBySession(f.session)
                .size,
        )
        assertEquals(
            2,
            f.storage.toolCalls
                .listByTurn(f.turn)
                .size,
        )
        assertTrue(DetachedJobDashboard.read(f.storage).single { it.callId == job.callId }.settlementPending)
    }

    private fun action(
        job: BackgroundJobUi,
        action: BackgroundJobAction,
    ) {
        val chat = app.appContainer.chatService
        chat.performBackgroundJobAction(job, action)
        compose.waitUntil(30_000) {
            val state = chat.backgroundJobAction.value
            state?.callId == job.callId && !state.busy
        }
        val expected =
            if (action == BackgroundJobAction.COLLECT) {
                BackgroundJobActionOutcome.REBOOT_REQUIRED
            } else {
                BackgroundJobActionOutcome.REVIEW_REQUIRED
            }
        assertEquals(expected, chat.backgroundJobAction.value?.outcome)
    }

    private fun killRuntime() {
        val supervisor = ProotRuntimeSupervisor(app)
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
