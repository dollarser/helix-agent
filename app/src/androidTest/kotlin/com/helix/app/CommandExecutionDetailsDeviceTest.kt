package com.helix.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.ModelEvent
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * HXA-194 slice 3 — the command details journey on a real device, run by BOTH flavor test
 * variants: the task page's 命令 dialog lists every Linux command of a turn and each entry
 * opens the details page; the chat tool row's 详情 opens the same page; 返回 returns to
 * EXACTLY the page the detail was opened from (task dashboard or conversation); 返回会话
 * switches to the owning session; and opening, rebuilding (rotation equivalent) or
 * backgrounding/returning the page re-projects the SAME persisted facts and starts, replays
 * or acknowledges NOTHING — the durable rows (turns, tool calls, audit events) are asserted
 * unchanged in every journey.
 *
 * The fixture is one COMPLETED turn with four settled bash calls covering the four
 * non-running projections: succeeded (persisted content), failed (summary + exit code),
 * cancelled (detail line) and unknown (no settled result row). No call has a prepared-job
 * binding, so NEITHER flavor may show a binding section or any execution/recovery surface
 * on the page — the developer's archive-over-content quadrant has its own fixture in the
 * developer test variant (CommandResultBrowseDeviceTest).
 */
@RunWith(AndroidJUnit4::class)
class CommandExecutionDetailsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedCommandDetailFixture() {
        val storage = containerFromApp().storage
        if (storage.sessions.list().none { it.id == SESSION }) {
            storage.sessions.create(
                SESSION,
                "HXA194 command detail fixture",
                null,
                null,
                SystemClock().now().toEpochMilli(),
            )
        }
        if (storage.turns.find(TURN) == null) {
            seedTurn(storage)
        }
        seedSettledCall(
            storage,
            CALL_SUCCEEDED,
            """{"command":["echo hello"]}""",
            "COMPLETED",
            resultStatus = "SUCCEEDED",
            resultSummary = "Command succeeded",
            resultContent = """{"state":"SUCCEEDED","exitCode":0,"stdout":"hello from fixture","stderr":""}""",
        )
        seedSettledCall(
            storage,
            CALL_FAILED,
            """{"command":["false"]}""",
            "FAILED",
            resultStatus = "FAILED",
            resultSummary = "Command failed with exit code 3",
        )
        seedSettledCall(
            storage,
            CALL_CANCELLED,
            """{"command":["sleep 30"]}""",
            "CANCELLED",
            resultStatus = "FAILED",
            resultSummary = "user cancelled",
        )
        // The call settled but its result row was never persisted: the page must
        // report "unknown", never guess.
        seedSettledCall(storage, CALL_UNKNOWN, """{"command":["echo ghost"]}""", "COMPLETED")
    }

    private fun seedTurn(storage: HelixStorage) {
        TurnCoordinator
            .start(
                storage,
                SystemClock(),
                { UUID.randomUUID().toString() },
                TurnStartSpec(SESSION, TURN, "$TURN-model", "command-fixture", "HXA194 command fixture"),
            ).apply {
                beginModelStream().apply(ModelEvent.TextDelta("HXA194 command fixture result"))
                terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            }
    }

    /** One settled bash call of the fixture, with (or without) its persisted result row. */
    private fun seedSettledCall(
        storage: HelixStorage,
        callId: String,
        argsJson: String,
        state: String,
        resultStatus: String? = null,
        resultSummary: String? = null,
        resultContent: String? = null,
    ) {
        if (storage.toolCalls.listByTurn(TURN).none { it.callId == callId }) {
            // The production contract: the row's primary key id IS the call id (the
            // tool_results FK references tool_calls.id, not the call_id column).
            storage.toolCalls.append(
                callId,
                TURN,
                callId,
                "bash",
                "1",
                argsJson,
                state,
            )
            if (resultStatus != null) {
                storage.toolResults.append(
                    "hxa194-result-$callId",
                    callId,
                    resultStatus,
                    requireNotNull(resultSummary),
                    resultContent,
                )
            }
        }
    }

    /** The task-page entry: the 命令 dialog lists every command call and each opens its page. */
    @Test
    fun taskPageCommandsOpenEachSettledStateAndReturnToTheDashboard() {
        val storage = containerFromApp().storage
        compose.resetDeterministicUiState()
        val before = fixtureFacts(storage)
        compose.navigateTo("tasks")
        waitTurnRowVisible(TURN)

        openCommandDetailFromTasks(CALL_SUCCEEDED)
        compose.onNodeWithTag("command-detail-state-succeeded").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-command").assertTextContains("echo hello")
        compose.onNodeWithTag("command-detail-stdout").assertTextContains("hello from fixture")
        compose.onNodeWithTag("command-detail-exit").assertIsDisplayed()
        assertNoDetailLineOrExecutionSurface(CALL_SUCCEEDED)
        backToDashboard()

        openCommandDetailFromTasks(CALL_FAILED)
        compose.onNodeWithTag("command-detail-state-failed").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-detail").assertTextContains("Command failed with exit code 3")
        compose.onNodeWithTag("command-detail-exit").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-no-output").assertIsDisplayed()
        assertNoExecutionSurface(CALL_FAILED)
        backToDashboard()

        openCommandDetailFromTasks(CALL_CANCELLED)
        compose.onNodeWithTag("command-detail-state-cancelled").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-detail").assertTextContains("user cancelled")
        assertNoExecutionSurface(CALL_CANCELLED)
        backToDashboard()

        openCommandDetailFromTasks(CALL_UNKNOWN)
        compose.onNodeWithTag("command-detail-state-unknown").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-no-output").assertIsDisplayed()
        assertNoDetailLineOrExecutionSurface(CALL_UNKNOWN)
        backToDashboard()

        assertEquals("opening the details must start or record nothing", before, fixtureFacts(storage))
    }

    /** The chat tool row's 详情 opens the same page and 返回 comes back to the conversation. */
    @Test
    fun chatToolRowOpensTheDetailPageAndBackReturnsToTheConversation() {
        val container = containerFromApp()
        val chat = container.chatService
        val storage = container.storage
        compose.resetDeterministicUiState()
        val before = fixtureFacts(storage)
        // The session list is a process-level service cache while the fixture rows were
        // written to storage directly, so reload through the production seam before
        // waiting for this fixture's row.
        chat.refreshSessions()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-session-$SESSION").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-session-$SESSION").performScrollTo().performClick()
        compose.waitForIdle()
        stopAwait { chat.screen.value.openSessionId == SESSION }
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("command-detail-$CALL_FAILED").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("command-detail-$CALL_FAILED").performScrollTo().performClick()
        compose.waitForIdle()
        waitDetailScreenVisible()
        compose.onNodeWithTag("command-detail-state-failed").assertIsDisplayed()
        // 返回 returns to the page the detail was opened from: this session's conversation.
        compose.onNodeWithTag("command-detail-back").performClick()
        compose.waitForIdle()
        stopAwait { chat.screen.value.openSessionId == SESSION }
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tool-row-$CALL_FAILED").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(before, fixtureFacts(storage))
    }

    /** 返回会话 from the page switches to the owning session — even when it is not the open one. */
    @Test
    fun openSessionFromTheDetailPageSwitchesToTheOwningSession() {
        val container = containerFromApp()
        val chat = container.chatService
        val storage = container.storage
        compose.resetDeterministicUiState()
        val before = fixtureFacts(storage)
        compose.navigateTo("tasks")
        waitTurnRowVisible(TURN)
        openCommandDetailFromTasks(CALL_SUCCEEDED)
        compose.onNodeWithTag("command-detail-open-session").performClick()
        compose.waitForIdle()
        stopAwait { chat.screen.value.openSessionId == SESSION }
        compose.onNodeWithTag("screen-sessions").assertIsDisplayed()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tool-row-$CALL_SUCCEEDED").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("opening the owning session must start nothing", before, fixtureFacts(storage))
    }

    /**
     * Opening the page, rebuilding the activity (the path a rotation takes) and — after a
     * background/foreground cycle, re-opening the page from the task dashboard (the launch
     * intent brings the shell back at its root) — re-project the SAME persisted facts; the
     * system back from the rebuilt page returns to the task dashboard. Nothing is
     * restarted or re-recorded.
     */
    @Test
    fun reopeningAndRebuildingTheDetailPageRestoresFactsAndStartsNothing() {
        val storage = containerFromApp().storage
        compose.resetDeterministicUiState()
        val before = fixtureFacts(storage)
        compose.navigateTo("tasks")
        waitTurnRowVisible(TURN)
        openCommandDetailFromTasks(CALL_FAILED)
        compose.onNodeWithTag("command-detail-state-failed").assertIsDisplayed()

        // Rotation equivalent: the activity rebuild restores the same back stack and the
        // page re-reads its facts. The state tag only exists once that read resolved, so
        // wait for it before asserting the projection.
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("command-detail-state-failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("command-detail-state-failed").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-detail").assertTextContains("Command failed with exit code 3")

        // Background and return: the launch intent brings the shell back at its root, so
        // re-open the page from the task dashboard — it must re-project the SAME facts.
        backgroundAndReturn()
        compose.navigateTo("tasks")
        openCommandDetailFromTasks(CALL_FAILED)
        compose.onNodeWithTag("command-detail-state-failed").assertIsDisplayed()

        // System back (same dispatcher the platform back invokes) returns to the source.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        waitTurnRowVisible(TURN)
        assertEquals(before, fixtureFacts(storage))
    }

    // ---------- fixture fact checks ----------

    /** The durable rows a detail read must never grow: turns, tool calls, audit events. */
    private data class FixtureFacts(
        val turns: Int,
        val calls: Int,
        val audits: Int,
    )

    private fun fixtureFacts(storage: HelixStorage): FixtureFacts =
        FixtureFacts(
            storage.turns.listBySession(SESSION).size,
            storage.toolCalls.listByTurn(TURN).size,
            storage.auditEvents.listByCorrelation(SESSION).size,
        )

    // ---------- UI helpers ----------

    private fun waitTurnRowVisible(turnId: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tasks-turn-$turnId").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitDetailScreenVisible() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("screen-command-detail").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openCommandDetailFromTasks(callId: String) {
        waitTurnRowVisible(TURN)
        compose.onNodeWithTag("tasks-turn-command-$TURN").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("turn-command-detail-$callId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("turn-command-detail-$callId").performScrollTo().performClick()
        compose.waitForIdle()
        waitDetailScreenVisible()
    }

    private fun backToDashboard() {
        compose.onNodeWithTag("command-detail-back").performClick()
        compose.waitForIdle()
        waitTurnRowVisible(TURN)
    }

    private fun backgroundAndReturn() {
        compose.activity.moveTaskToBack(false)
        Thread.sleep(1_500)
        val intent = compose.activity.intent
        if (intent != null) compose.activity.startActivity(intent)
        compose.waitForIdle()
    }

    private fun assertNoExecutionSurface(callId: String) {
        // The details page is a pure read: no proot recovery, no re-run, no acknowledgement.
        listOf("proot-result-$callId", "proot-query-$callId", "proot-stop-$callId").forEach { tag ->
            assertTrue(
                "the details page must not offer an execution surface ($tag)",
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    private fun assertNoDetailLineOrExecutionSurface(callId: String) {
        assertTrue(
            "a succeeded/unknown projection has no detail line",
            compose.onAllNodesWithTag("command-detail-detail").fetchSemanticsNodes().isEmpty(),
        )
        assertNoExecutionSurface(callId)
    }

    private fun containerFromApp(): AppContainer =
        (ApplicationProvider.getApplicationContext<Application>() as HelixApplication).appContainer

    /** Polls [condition] every 25 ms for up to 30 s. */
    private fun stopAwait(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("production state must settle", condition())
    }

    companion object {
        private const val SESSION = "hxa194-cmd-session"
        private const val TURN = "hxa194-cmd-turn"
        private const val CALL_SUCCEEDED = "hxa194-cmd-succeeded"
        private const val CALL_FAILED = "hxa194-cmd-failed"
        private const val CALL_CANCELLED = "hxa194-cmd-cancelled"
        private const val CALL_UNKNOWN = "hxa194-cmd-unknown"
    }
}
