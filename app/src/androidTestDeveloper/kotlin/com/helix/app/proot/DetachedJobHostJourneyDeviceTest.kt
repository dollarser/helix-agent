package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.app.ui.resetDeterministicUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Properties

/** No Job runs during preparation. The owned host drives the ordinary application afterward. */
class DetachedJobHostJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val marker get() = File(app.noBackupFilesDir, "host-job-journey.properties")

    @Test fun prepareOrVerifyOrdinaryProcessJourney() =
        runBlocking {
            val phase = InstrumentationRegistry.getArguments().getString("hostJobPhase")
            assumeTrue("Requires the owned ordinary-process host runner", phase != null)
            require(phase in setOf("prepare", "verify"))
            if (phase == "verify") verify() else prepare()
        }

    private suspend fun prepare() {
        compose.resetDeterministicUiState()
        ScriptedTaskModelServer().use { server ->
            server.start()
            val f = DetachedGoalFixture(app, server)
            f.prepare()
            val facts =
                Properties().apply {
                    setProperty("session", f.session)
                    setProperty("port", server.port.toString())
                    setProperty("output", f.output.name)
                }
            marker.outputStream().use {
                facts.store(it, "Synthetic ordinary-process fixture")
                it.fd.sync()
            }
            assertEquals(
                0,
                f.storage.turns
                    .listBySession(f.session)
                    .size,
            )
        }
    }

    private fun verify() {
        val facts = Properties().apply { marker.inputStream().use { load(it) } }
        val storage = app.appContainer.storage
        val session = facts.getProperty("session")
        val turn = storage.turns.listBySession(session).single()
        assertEquals("COMPLETED", turn.state)
        assertEquals(1, storage.toolCalls.listByTurn(turn.id).size)
        val job = DetachedJobDashboard.read(storage).single { it.sessionId == session }
        repeat(2) {
            val chat = app.appContainer.chatService
            chat.performBackgroundJobAction(job, BackgroundJobAction.COLLECT)
            compose.waitUntil(30_000) {
                val action = chat.backgroundJobAction.value
                action?.callId == job.callId && !action.busy
            }
            assertEquals(BackgroundJobActionOutcome.SETTLED, chat.backgroundJobAction.value?.outcome)
        }
        assertNull(ExecutionOwnershipStore(File(app.filesDir, "execution-admission/owner")).read())
        assertFalse(DetachedJobDashboard.read(storage).single { it.callId == job.callId }.settlementPending)
        val output = File(app.filesDir, "workspaces/app/output/${facts.getProperty("output")}")
        assertEquals("host-job-result", output.readText())
        assertEquals(1, storage.turns.listBySession(session).size)
        assertEquals(1, storage.toolCalls.listByTurn(turn.id).size)
    }
}
