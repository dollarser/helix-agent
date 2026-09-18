package com.helix.app

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * HXA-204 slice 4 — the cross-execution-domain recovery journey on a real device.
 *
 * Five persisted failure facts (network, expired auth, revoked permission, an interrupted turn
 * with a parked side effect, and a user cancel) are seeded through the production coordinator,
 * then the app process is killed (two-phase `recoveryPhase` protocol, D8). On restart the test
 * proves the facts survive under a new PID with zero re-execution, and that each real recovery
 * entry point does exactly what its class promises: reconnect/grant only repair (no re-submit),
 * query only reconciles a parked call (never replays), a cancel renders no auto-continue, and a
 * retry after a live network failure produces exactly one new call that supersedes the failure.
 *
 * The seeded turns carry no provider and no user message, so the retry *admission* (a turn-state
 * fact) renders `chat-retry` but executing it is a no-op here — the live journey below exercises
 * the real new-call path with a loopback provider instead.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions")
class RecoveryJourneyDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedStandaloneFixture() {
        if (recoveryPhase() == null) seedRecoveryHistory(containerFromApp())
    }

    // ---- two-phase process-death recovery (D8 protocol): facts persist, nothing re-executes ----

    @Test
    fun recoveryFactsSurviveProcessDeathWithoutReexecution() {
        val container = containerFromApp()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val phase = recoveryPhase()
        if (phase == "verify") {
            val markerPid = readRecoveryMarker(context)
            assertNotEquals("the app process must restart under a new pid", markerPid, Process.myPid())
            assertSeededRecovery(container)
            return
        }
        seedRecoveryHistory(container)
        if (phase == "setup") {
            writeRecoveryMarker(context)
            Process.killProcess(Process.myPid())
            error("the process must die once the marker is published")
        }
        assertSeededRecovery(container)
    }

    // ---- recovery-button journeys: each operation does exactly its class, never a replay ----

    @Test
    fun networkFailureOffersReconnectAndNeverAutoReplays() {
        assumeTrue(recoveryPhase() != "setup")
        val container = compose.container()
        val turnId = openAndAwaitPanel(SESSION_NET)
        val turnCount =
            container.storage.turns
                .listBySession(SESSION_NET)
                .size
        compose.onNodeWithTag("recovery-reconnect-$turnId").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertIsDisplayed()
        compose.onNodeWithTag("recovery-reconnect-$turnId").performClick()
        awaitProvidersScreen()
        assertEquals(
            "reconnect repairs the connection and must not re-execute the turn",
            turnCount,
            container.storage.turns
                .listBySession(SESSION_NET)
                .size,
        )
    }

    @Test
    fun expiredAuthFixtureKeepsReconnectAndRetryAdmission() {
        assumeTrue(recoveryPhase() != "setup")
        val turnId = openAndAwaitPanel(SESSION_AUTH)
        // No recovery-reason line for AUTH (only RESULT_UNKNOWN and CANCELLED add one); the turn's
        // error area already shows the code. Assert the two admitted operations.
        compose.onNodeWithTag("recovery-reconnect-$turnId").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertIsDisplayed()
    }

    @Test
    fun revokedPermissionOffersGrantAndNeverAutoReplays() {
        assumeTrue(recoveryPhase() != "setup")
        val container = compose.container()
        val turnId = openAndAwaitPanel(SESSION_CAP)
        val turnCount =
            container.storage.turns
                .listBySession(SESSION_CAP)
                .size
        compose.onNodeWithTag("recovery-grant-$turnId").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertIsDisplayed()
        compose.onNodeWithTag("recovery-grant-$turnId").performClick()
        awaitProvidersScreen()
        assertEquals(
            "granting permission must not re-execute the turn",
            turnCount,
            container.storage.turns
                .listBySession(SESSION_CAP)
                .size,
        )
    }

    @Test
    fun unknownResultIsQueryOnlyAndARepeatQueryStaysNoop() {
        assumeTrue(recoveryPhase() != "setup")
        val container = compose.container()
        val storage = container.storage
        val turnId = openAndAwaitPanel(SESSION_INT)
        compose.onNodeWithTag("recovery-reason-$turnId").assertIsDisplayed()
        compose.onNodeWithTag("recovery-query-$turnId").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertDoesNotExist()
        val parkedBefore = storage.toolCalls.listByTurn(turnId).single()
        compose.onNodeWithTag("recovery-query-$turnId").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recovery-query-$turnId").performClick()
        compose.waitForIdle()
        assertEquals("a repeated query must not re-execute the turn", 1, storage.turns.listBySession(SESSION_INT).size)
        assertEquals(
            "the parked side effect is only reconciled, never replayed",
            parkedBefore.state,
            storage.toolCalls
                .listByTurn(turnId)
                .single()
                .state,
        )
    }

    @Test
    fun cancelledTurnRendersNoAutoContinueOperation() {
        assumeTrue(recoveryPhase() != "setup")
        val turnId = openAndAwaitPanel(SESSION_CANCEL)
        compose.onNodeWithTag("recovery-reason-$turnId").assertIsDisplayed()
        compose.onNodeWithTag("chat-retry").assertDoesNotExist()
        compose.onNodeWithTag("recovery-continue-$turnId").assertDoesNotExist()
        compose.onNodeWithTag("recovery-reconnect-$turnId").assertDoesNotExist()
    }

    // ---- the live new-call path: a retry after a real network failure is one fresh call, not a replay ----

    @Test
    fun retryAfterNetworkFailureIsExactlyOneNewCallNotAReplay() {
        assumeTrue(recoveryPhase() != "setup")
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val previous = chat.runControl.value
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(container, server.port)
                val session = chat.createSession("HXA204 recovery retry fixture", provider, MODEL)
                try {
                    chat.openSession(session)
                    chat.setMode(AgentMode.CHAT)
                    chat.setTurnBudgets(TurnBudgets(3, 4, 65536, 128, 65664))
                    server.holdChatStreams.set(true)
                    chat.send("Recover me, please.")
                    settle { server.heldStreams.get() == 1 }
                    // A peer close mid-stream is the production 断网 outcome: a FAILED transport turn.
                    requireNotNull(server.heldSocket.get()).close()
                    settle {
                        chat.screen.value.activeTurn
                            ?.state
                            ?.name == "FAILED"
                    }
                    compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { compose.onNodeWithTag("chat-retry").isDisplayed() }
                    assertEquals(
                        "only the failed turn exists before the retry",
                        1,
                        storage.turns.listBySession(session).size,
                    )
                    server.holdChatStreams.set(false)
                    compose.onNodeWithTag("chat-retry").performClick()
                    settle {
                        storage.turns.listBySession(session).size == 2 &&
                            chat.screen.value.activeTurn
                                ?.state
                                ?.name == "COMPLETED"
                    }
                    assertEquals(listOf("FAILED", "COMPLETED"), storage.turns.listBySession(session).map { it.state })
                    compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                        compose.onAllNodesWithTag("chat-retry").fetchSemanticsNodes().isEmpty()
                    }
                } finally {
                    chat.stop()
                    settle { !chat.screen.value.isSending }
                    chat.closeSession()
                    chat.setMode(previous.mode)
                    chat.setTurnBudgets(previous.budgets)
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(provider)
                }
            }
        }
    }

    // ---- helpers ----

    private fun openAndAwaitPanel(sessionId: String): String {
        val container = compose.container()
        val turnId =
            container.storage.turns
                .listBySession(sessionId)
                .single()
                .id
        compose.resetDeterministicUiState()
        container.chatService.openSession(sessionId)
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("recovery-panel-$turnId").fetchSemanticsNodes().isNotEmpty()
        }
        return turnId
    }

    private fun awaitProvidersScreen() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("provider-add").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seedRecoveryHistory(container: AppContainer) {
        val storage = container.storage
        val clock = SystemClock()
        val now = clock.now().toEpochMilli()
        seedSession(storage, SESSION_NET, "HXA204 recovery network")
        seedTerminalTurn(storage, clock, SESSION_NET, TURN_NET, "TRANSPORT", TurnState.FAILED, now)
        seedSession(storage, SESSION_AUTH, "HXA204 recovery expired auth")
        seedTerminalTurn(storage, clock, SESSION_AUTH, TURN_AUTH, "AUTH", TurnState.FAILED, now)
        seedSession(storage, SESSION_CAP, "HXA204 recovery revoked permission")
        seedTerminalTurn(storage, clock, SESSION_CAP, TURN_CAP, "PERMISSION", TurnState.FAILED, now)
        seedSession(storage, SESSION_INT, "HXA204 recovery unknown result")
        seedTerminalTurn(storage, clock, SESSION_INT, TURN_INT, null, TurnState.INTERRUPTED, now)
        if (storage.toolCalls.listByTurn(TURN_INT).isEmpty()) {
            storage.toolCalls.append("$TURN_INT-bash", TURN_INT, "call-bash", "bash", "1", "{}", "NEEDS_REVIEW")
        }
        seedSession(storage, SESSION_CANCEL, "HXA204 recovery cancelled")
        seedTerminalTurn(storage, clock, SESSION_CANCEL, TURN_CANCEL, null, TurnState.CANCELLED, now)
    }

    private fun seedSession(
        storage: HelixStorage,
        sessionId: String,
        title: String,
    ) {
        if (storage.sessions.list().none { it.id == sessionId }) {
            storage.sessions.create(sessionId, title, null, null, SystemClock().now().toEpochMilli())
        }
    }

    private fun seedTerminalTurn(
        storage: HelixStorage,
        clock: SystemClock,
        sessionId: String,
        turnId: String,
        errorCode: String?,
        target: TurnState,
        createdAt: Long,
    ) {
        if (storage.turns.find(turnId) != null) return
        TurnCoordinator.start(
            storage,
            clock,
            { UUID.randomUUID().toString() },
            TurnStartSpec(sessionId, turnId, "$turnId-model", "recovery-fixture", null),
        )
        val started = storage.turns.resolve(turnId)
        // endedAt == startedAt satisfies the repository's endedAt >= startedAt invariant.
        val endedAt = started.startedAt.coerceAtLeast(createdAt)
        var turn = started
        when (target) {
            TurnState.FAILED -> {
                turn = storage.turns.updateState(turn, TurnState.FAILED, 0, endedAt, errorCode)
            }

            TurnState.INTERRUPTED -> {
                turn = storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, null, null)
            }

            TurnState.CANCELLED -> {
                turn = storage.turns.updateState(turn, TurnState.CANCELLING, 0, null, null)
                turn = storage.turns.updateState(turn, TurnState.CANCELLED, 0, endedAt, null)
            }

            else -> {
                error("unsupported recovery fixture state $target")
            }
        }
    }

    private fun assertSeededRecovery(container: AppContainer) {
        val storage = container.storage
        for (sessionId in listOf(SESSION_NET, SESSION_AUTH, SESSION_CAP, SESSION_INT, SESSION_CANCEL)) {
            assertEquals(
                "the fixture session keeps exactly its one seeded turn",
                1,
                storage.turns.listBySession(sessionId).size,
            )
        }
        assertEquals("FAILED", storage.turns.resolve(TURN_NET).state)
        assertEquals("TRANSPORT", storage.turns.resolve(TURN_NET).errorCode)
        assertEquals("FAILED", storage.turns.resolve(TURN_AUTH).state)
        assertEquals("AUTH", storage.turns.resolve(TURN_AUTH).errorCode)
        assertEquals("FAILED", storage.turns.resolve(TURN_CAP).state)
        assertEquals("PERMISSION", storage.turns.resolve(TURN_CAP).errorCode)
        assertEquals("INTERRUPTED", storage.turns.resolve(TURN_INT).state)
        assertEquals("CANCELLED", storage.turns.resolve(TURN_CANCEL).state)
        val parked = storage.toolCalls.listByTurn(TURN_INT).single()
        assertEquals("NEEDS_REVIEW", parked.state)
        assertEquals("call-bash", parked.callId)
    }

    private fun containerFromApp(): AppContainer =
        (ApplicationProvider.getApplicationContext<Application>() as HelixApplication).appContainer

    private fun recoveryPhase(): String? = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)

    private fun writeRecoveryMarker(context: Context) {
        context.noBackupFilesDir.resolve(PID_MARKER).writeText(Process.myPid().toString())
    }

    private fun readRecoveryMarker(context: Context): Int {
        val pid =
            context.noBackupFilesDir
                .resolve(PID_MARKER)
                .readText()
                .trim()
                .toInt()
        assertTrue("the recovery marker must hold a numeric pid", pid > 0)
        return pid
    }

    private suspend fun createProvider(
        container: AppContainer,
        port: Int,
    ): String {
        val service = container.providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "HXA204 recovery retry provider",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    MODEL,
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        check(service.runConnectionTest(id) is ProbeOutcome.Ok)
        return id
    }

    private fun settle(condition: () -> Boolean) {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("the production state must settle", condition())
    }

    private companion object {
        const val RECOVERY_PHASE_KEY = "recoveryPhase"
        const val PID_MARKER = "recovery-device-pid"
        const val SETTLE_TIMEOUT_NANOS = 30_000_000_000L
        const val MODEL = "fixture-model-a"
        const val SESSION_NET = "recovery-net-session"
        const val TURN_NET = "recovery-net-turn"
        const val SESSION_AUTH = "recovery-auth-session"
        const val TURN_AUTH = "recovery-auth-turn"
        const val SESSION_CAP = "recovery-cap-session"
        const val TURN_CAP = "recovery-cap-turn"
        const val SESSION_INT = "recovery-int-session"
        const val TURN_INT = "recovery-int-turn"
        const val SESSION_CANCEL = "recovery-cancel-session"
        const val TURN_CANCEL = "recovery-cancel-turn"
    }
}
