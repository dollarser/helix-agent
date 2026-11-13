package com.helix.feature.conversation.presentation

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.TurnState
import com.helix.core.model.input.SessionInputDelivery
import com.helix.feature.conversation.R
import com.helix.feature.conversation.application.input.ConversationInputQueue
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.feature.conversation.input.PendingInputView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.helix.ui.shared.R as SharedR

/** Real screen + queue panel interactions; only the application command boundary is faked. */
class SessionInputQueuePanelDeviceTest : ConversationDeviceTestFixture() {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val queue = RecordingInputQueue()
    private val activeTurn = mutableStateOf<String?>(null)

    @Test fun delegatedQueueShowsOriginAndOnlySupportedActions() {
        queue.record = checkNotNull(queue.record).copy(delegationSourceSessionId = "source-session")
        render()
        compose
            .onNodeWithTag("session-input-origin-queued")
            .assertTextEquals(context.getString(R.string.session_input_delegated_from, "source-session"))
        compose.onNodeWithTag("session-input-edit-action-queued").assertDoesNotExist()
        compose.onNodeWithTag("session-input-send-now-queued").assertDoesNotExist()
        compose.onNodeWithTag("session-input-delete-queued").assertIsDisplayed()
    }

    @Test fun withdrawalUsesDisplayedRevisionAndKeepsTheInputUntilTheCommandAcceptsIt() {
        val outcome = CompletableDeferred<Boolean>()
        queue.onWithdraw = { outcome }
        render()
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
        compose.onNodeWithTag("session-input-delete-queued").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("session-input-edit-action-queued").assertIsNotEnabled()
        compose.onNodeWithTag("session-input-send-now-queued").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("queued" to 7L), queue.withdrawals)
            assertEquals(0, queue.edits.size + queue.sends.size + queue.resumes.size)
            queue.record = null
            outcome.complete(true)
        }
        awaitQueueHidden()
        compose.onNodeWithTag("chat-input").assertTextEquals("Separate composer draft")
    }

    @Test fun staleWithdrawalRetainsTheRefreshedInputAndTheNextActionUsesItsNewRevision() {
        queue.onWithdraw = {
            queue.record = checkNotNull(queue.record).copy(revision = 8)
            queue.text = "Updated in another view"
            CompletableDeferred(false)
        }
        render()
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        compose.onNodeWithText(context.getString(R.string.session_input_revision_stale)).assertIsDisplayed()
        compose.onNodeWithTag("session-input-preview-text-queued").assertTextEquals("Updated in another view")
        compose.runOnIdle {
            assertEquals(listOf("queued" to 7L), queue.withdrawals)
            queue.onWithdraw = null
        }
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        awaitQueueHidden()
        compose.runOnIdle { assertEquals(listOf("queued" to 7L, "queued" to 8L), queue.withdrawals) }
    }

    @Test fun failedWithdrawalPreservesSavedInputAndRetryReloadsWithoutRepeatingTheCommand() {
        queue.onWithdraw = {
            CompletableDeferred<Boolean>().apply { completeExceptionally(IllegalStateException("Synthetic failure")) }
        }
        render()
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        compose.onNodeWithText(context.getString(R.string.session_input_action_failed)).assertIsDisplayed()
        compose.onNodeWithTag("session-input-preview-text-queued").assertTextEquals("Saved queued input")
        compose.onNodeWithText(context.getString(SharedR.string.chat_retry)).performClick()
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("queued" to 7L), queue.withdrawals)
            assertEquals(0, queue.edits.size + queue.sends.size + queue.resumes.size)
        }
    }

    @Test fun cancelledWithdrawalNeverRemovesTheInputAndReleasesTheBusyState() {
        val outcome = CompletableDeferred<Boolean>()
        queue.onWithdraw = { outcome }
        render()
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        compose.onNodeWithTag("session-input-delete-queued").assertIsNotEnabled()
        compose.runOnIdle {
            outcome.cancel()
            queue.onWithdraw = null
        }
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.session_input_action_failed)).assertDoesNotExist()
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        awaitQueueHidden()
        compose.runOnIdle { assertEquals(listOf("queued" to 7L, "queued" to 7L), queue.withdrawals) }
    }

    @Test fun editorReadsFullSavedTextAndCancelThenSaveNeverSubmitsTheQueue() {
        val original = "Saved input beyond the compact preview. ".repeat(12)
        queue.text = original
        render()
        compose.onNodeWithTag("session-input-edit-action-queued").performClick()
        compose.onNodeWithTag("session-input-edit-queued").assertTextEquals(original)
        compose.onNodeWithTag("session-input-edit-queued").performTextReplacement("Discarded edit")
        compose.onNodeWithText(context.getString(SharedR.string.common_cancel)).performClick()
        compose.onNodeWithTag("session-input-edit-queued").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(original, queue.text)
            assertEquals(emptyList<Triple<String, Long, String>>(), queue.edits)
        }
        compose.onNodeWithTag("session-input-edit-action-queued").performClick()
        compose.onNodeWithTag("session-input-edit-queued").assertTextEquals(original)
        compose.onNodeWithTag("session-input-edit-queued").performTextReplacement("Saved revision")
        compose.onNodeWithText(context.getString(R.string.session_input_save)).performClick()
        compose.onNodeWithTag("session-input-edit-queued").assertDoesNotExist()
        compose.onNodeWithTag("session-input-preview-text-queued").assertTextEquals("Saved revision")
        compose.runOnIdle {
            assertEquals(listOf(Triple("queued", 7L, "Saved revision")), queue.edits)
            assertEquals(0, queue.withdrawals.size + queue.sends.size + queue.resumes.size)
        }
    }

    @Test fun sendNowUsesTheDisplayedTurnAndAttentionInputsGoThroughResumeInstead() {
        activeTurn.value = "active-turn"
        render()
        compose.onNodeWithTag("session-input-send-now-queued").performClick()
        awaitQueueHidden()
        compose.runOnIdle {
            assertEquals(listOf(Triple("queued", 7L, "active-turn")), queue.sends)
            assertEquals(emptyList<Pair<String, Long>>(), queue.resumes)
            queue.record = PendingInputView("attention", 9, SessionInputDelivery.QUEUE, needsAttention = true)
            queue.text = "Recover this input"
            queue.delivery.value++
        }
        compose.onNodeWithTag("session-input-send-now-attention").performClick()
        awaitQueueHidden()
        compose.runOnIdle {
            assertEquals(listOf("attention" to 9L), queue.resumes)
            assertEquals(listOf(Triple("queued", 7L, "active-turn")), queue.sends)
        }
    }

    private fun awaitQueueHidden() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("session-input-queue-panel").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun render() {
        compose.setContent {
            MaterialTheme {
                val screen =
                    conversationScreenFixture().copy(
                        activeTurn = activeTurn.value?.let { TurnUi(it, TurnState.RECEIVING_MODEL, null, null, false) },
                    )
                ChatScreen(
                    screen = screen,
                    runControl = RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT),
                    input = "Separate composer draft",
                    onInput = {},
                    bindableProviders = emptyList(),
                    intents =
                        conversationIntentsFixture(
                            onSend = { error("Queue actions must not send the composer") },
                        ),
                    composerAvailability = ComposerAvailability(modelSelected = false),
                    composerStatus = {
                        SessionInputQueuePanel(queue, "session", activeTurnId = screen.activeTurn?.id)
                    },
                )
            }
        }
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
    }
}

private class RecordingInputQueue : ConversationInputQueue {
    var record: PendingInputView? = PendingInputView("queued", 7, SessionInputDelivery.QUEUE, needsAttention = false)
    var text = "Saved queued input"
    val delivery = MutableStateFlow(0L)
    val withdrawals = mutableListOf<Pair<String, Long>>()
    val edits = mutableListOf<Triple<String, Long, String>>()
    val sends = mutableListOf<Triple<String, Long, String>>()
    val resumes = mutableListOf<Pair<String, Long>>()
    var onWithdraw: (() -> Deferred<Boolean>)? = null

    override fun pendingInputViews(sessionId: String): Deferred<List<PendingInputView>> {
        check(sessionId == "session")
        return CompletableDeferred(listOfNotNull(record))
    }

    override fun readSessionInput(inputId: String): Deferred<String?> =
        CompletableDeferred(text.takeIf { inputId == record?.inputId })

    override fun observeSessionInputDelivery(sessionId: String): Flow<Long> {
        check(sessionId == "session")
        return delivery
    }

    override fun withdrawSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): Deferred<Boolean> {
        withdrawals += inputId to expectedRevision
        onWithdraw?.let { return it() }
        check(record?.inputId == inputId && record?.revision == expectedRevision)
        record = null
        return CompletableDeferred(true)
    }

    override fun editSessionInput(
        inputId: String,
        expectedRevision: Long,
        text: String,
    ): Deferred<Boolean> {
        edits += Triple(inputId, expectedRevision, text)
        check(record?.inputId == inputId && record?.revision == expectedRevision)
        this.text = text
        record = checkNotNull(record).copy(revision = expectedRevision + 1)
        return CompletableDeferred(true)
    }

    override fun sendQueuedInputNow(
        inputId: String,
        expectedRevision: Long,
        expectedTurnId: String,
    ): Deferred<Boolean> {
        sends += Triple(inputId, expectedRevision, expectedTurnId)
        check(record?.inputId == inputId && record?.revision == expectedRevision)
        record = null
        return CompletableDeferred(true)
    }

    override fun resumeSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): Deferred<ChatSubmissionOutcome> {
        resumes += inputId to expectedRevision
        check(record?.inputId == inputId && record?.revision == expectedRevision)
        record = null
        return CompletableDeferred(ChatSubmissionOutcome.Accepted("resumed-turn"))
    }
}
