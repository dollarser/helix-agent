package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.helix.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * HX2-07 (research doc section 16): the conversation's Progress section projects the
 * model's task ledger from its latest successful + verified `todo.write` — the rows
 * render in the model's order with the doc's markers, and later tool work on the same
 * turn does NOT invalidate the ledger (only a later successful `todo.write` replaces it).
 */
class TaskLedgerProgressDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun progressCardShowsTheLatestTodoWriteAndOutlivesLaterWork(): Unit =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val storage = container.storage
            val chat = container.chatService
            val sessionId = "ledger-${UUID.randomUUID()}"
            val turnId = "turn-${UUID.randomUUID()}"
            val callId = "call-${UUID.randomUUID()}"
            val ledgerArgs =
                """{"items":[
                {"id":"a","title":"Read the spec","state":"in_progress"},
                {"id":"b","title":"Run the gates","state":"todo"},
                {"id":"c","title":"Shipped","state":"done"},
                {"id":"d","title":"Waiting on CI","state":"blocked"}
                ]}""".replace(Regex("\\s+"), " ")
            storage.withTransaction {
                storage.sessions.create(sessionId, "Ledger fixture", null, null, 1000)
                storage.turns.start(id = turnId, sessionId = sessionId, startedAt = 2000)
                storage.toolCalls.append(callId, turnId, callId, "todo.write", "1", ledgerArgs, "COMPLETED")
                val result =
                    storage.toolResults.append(
                        "result-$callId",
                        callId,
                        "SUCCEEDED",
                        "Ledger recorded",
                        ledgerArgs,
                    )
                storage.toolResults.markVerified(result)
                // Later model work on the same turn must not invalidate the ledger.
                storage.toolCalls.append(
                    "later-${UUID.randomUUID()}",
                    turnId,
                    "later",
                    "time.now",
                    "1",
                    "{}",
                    "COMPLETED",
                )
            }
            try {
                chat.openSession(sessionId)
                compose.waitUntil { chat.screen.value.taskLedger.size == 4 }
                compose.onNodeWithTag("chat-ledger").assertIsDisplayed()
                compose.onNodeWithTag("chat-ledger-title").assertTextEquals("任务进度").assertIsDisplayed()
                compose.onNodeWithTag("chat-ledger-item-in_progress-0").assertIsDisplayed()
                compose.onNodeWithTag("chat-ledger-item-todo-1").assertIsDisplayed()
                compose.onNodeWithTag("chat-ledger-item-done-2").assertIsDisplayed()
                compose.onNodeWithTag("chat-ledger-item-blocked-3").assertIsDisplayed()
            } finally {
                chat.closeSession()
                storage.sessions.archive(sessionId, System.currentTimeMillis())
            }
        }
}
