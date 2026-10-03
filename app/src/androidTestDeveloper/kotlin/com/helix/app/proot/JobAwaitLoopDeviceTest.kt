package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ScriptedTaskModelServer
import com.helix.app.ui.resetDeterministicUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest

/** Real Provider stream, AgentLoop, Dispatcher and Runtime with a local scripted model, not a real account. */
class JobAwaitLoopDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun loopAwaitsOriginalExecutionThenCollectsVerifiedOutput() = journey(false)

    @Test fun openManualTerminalDoesNotBlockBackgroundTimeWaitAndCollect() = journey(true)

    private fun journey(withTerminal: Boolean): Unit =
        runBlocking {
            compose.resetDeterministicUiState()
            ScriptedTaskModelServer().use { server ->
                server.start()
                val fixture = DetachedGoalFixture(app, server)
                var terminalId: String? = null
                try {
                    fixture.prepare()
                    if (withTerminal) {
                        terminalId = requireNotNull(fixture.container.manualTerminal).start(".", 60_000).sessionId
                    }
                    fixture.submitAwaitJourney()
                    compose.waitUntil(60_000) {
                        fixture.storage.turns
                            .resolve(fixture.turn)
                            .state in
                            setOf("COMPLETED", "FAILED", "CANCELLED", "NEEDS_REVIEW", "INTERRUPTED")
                    }
                    assertEquals(
                        "COMPLETED",
                        fixture.storage.turns
                            .resolve(fixture.turn)
                            .state,
                    )
                    val allCalls = fixture.storage.toolCalls.listByTurn(fixture.turn)
                    assertEquals(4, allCalls.count { it.name == "tools.search" && it.state == "COMPLETED" })
                    val calls = allCalls.filter { it.name != "tools.search" }
                    assertEquals(
                        listOf(DetachedJobTools.START, "time.now", DetachedJobTools.AWAIT, DetachedJobTools.COLLECT),
                        calls.map { it.name },
                    )
                    assertTrue(calls.all { it.state == "COMPLETED" })
                    assertEquals(
                        1,
                        fixture.storage.turns
                            .listBySession(fixture.session)
                            .size,
                    )
                    assertEquals(sha256("await-result".toByteArray()), sha256(fixture.output.readBytes()))
                    val job = DetachedJobDashboard.read(fixture.storage).single { it.sessionId == fixture.session }
                    assertFalse(job.settlementPending)
                    assertObservationContext(fixture, calls.first().callId)
                    terminalId?.let { id ->
                        assertFalse(requireNotNull(fixture.container.manualTerminal).query(id).canSettle)
                    }
                } finally {
                    try {
                        cleanupJob(fixture)
                    } finally {
                        try {
                            closeTerminal(fixture, terminalId)
                        } finally {
                            fixture.close()
                        }
                    }
                }
            }
        }

    private suspend fun closeTerminal(
        fixture: DetachedGoalFixture,
        id: String?,
    ) {
        if (id == null) return
        val terminal = requireNotNull(fixture.container.manualTerminal)
        terminal.stop(id)
        kotlinx.coroutines.withTimeout(30_000) {
            while (!terminal.query(id).canSettle) kotlinx.coroutines.delay(100)
        }
        terminal.settle(id)
    }

    private fun assertObservationContext(
        fixture: DetachedGoalFixture,
        originalCallId: String,
    ) {
        val observations =
            fixture.storage.auditEvents.recentByCorrelation(
                fixture.session,
                com.helix.app.chat.JobObservationJournal.TYPE,
                64,
            )
        assertTrue(observations.isNotEmpty())
        val diagnostics =
            fixture.storage.modelCalls
                .listByTurn(fixture.turn)
                .flatMap { fixture.storage.auditEvents.listByCorrelation(it.id) }
        assertTrue(diagnostics.any { it.type == "context.job_observations" })
        val binding = ProotJobBindingStore(fixture.storage).resolveDetached(fixture.session, originalCallId)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ProotJobBindingStore(fixture.storage).resolveDetached("fork", binding.toolCallId)
        }
    }

    private suspend fun cleanupJob(fixture: DetachedGoalFixture) {
        // Never replay a failed fixture command or delete unresolved original execution evidence.
        if (fixture.turn.isEmpty()) return
        fixture.container.chatService.stopTask(fixture.turn)
        val jobs = DetachedJobDashboard.read(fixture.storage).filter { it.sessionId == fixture.session }
        jobs.filter { it.settlementPending }.forEach { job ->
            ProotToolModule.performBackgroundJobAction(job, BackgroundJobAction.CANCEL) { false }
            val binding = ProotJobBindingStore(fixture.storage).resolveDetached(job.sessionId, job.callId)
            val client =
                com.helix.runtime.proot.client
                    .DetachedJobClient(app)
            compose.waitUntil(30_000) {
                client
                    .query(binding)
                    .record
                    ?.state
                    ?.isTerminal == true
            }
            val result = ProotToolModule.performBackgroundJobAction(job, BackgroundJobAction.COLLECT) { false }
            check(result == BackgroundJobActionOutcome.SETTLED)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
