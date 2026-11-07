package com.helix.feature.conversation.presentation

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.feature.conversation.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the feature's real screen, including its default slots, without an App graph. */
class ChatScreenDeviceTest : ConversationDeviceTestFixture() {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val screen = mutableStateOf(conversationScreenFixture())
    private val input = mutableStateOf("Keep this unsent draft")
    private var sends = 0
    private var newSessions = 0

    @Test fun defaultScreenKeepsSecondarySurfacesClosedAndDetailsCanBeDismissedRepeatedly() {
        render()
        compose.onNodeWithTag("chat-header").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.onNodeWithTag("chat-empty-hint").assertTextEquals(context.getString(R.string.chat_empty_unbound))
        compose.onNodeWithTag("chat-model-list").assertDoesNotExist()
        compose.onNodeWithTag("chat-conversation-details-sheet").assertDoesNotExist()
        compose.onNodeWithTag("session-settings-open").assertDoesNotExist()
        compose.onNodeWithTag("background-tasks-open").assertDoesNotExist()
        repeat(2) {
            compose.onNodeWithTag("chat-conversation-details").performClick()
            compose.onNodeWithTag("session-settings-open").assertIsDisplayed()
            compose.onNodeWithTag("background-tasks-open").assertIsDisplayed()
            compose.onNodeWithTag("session-rename-open").assertIsDisplayed()
            compose.onNodeWithTag("chat-conversation-details-close").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("chat-conversation-details-sheet").fetchSemanticsNodes().isEmpty()
            }
        }
        compose.onNodeWithTag("chat-input").assertTextEquals("Keep this unsent draft")
        compose.runOnIdle {
            assertEquals(0, sends)
            assertEquals(0, newSessions)
        }
    }

    @Test fun missingModelNeverSendsAndBothPickerEntriesUseTheRealEmptyPicker() {
        render()
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, sends) }
        listOf("starter-choose-model", "chat-model-menu").forEach { entry ->
            compose.onNodeWithTag(entry).assertIsDisplayed().performClick()
            compose.onNodeWithTag("chat-model-list").assertIsDisplayed()
            compose.onNodeWithTag("chat-model-empty").assertIsDisplayed()
            compose.onNodeWithTag("chat-model-picker-save").assertIsNotEnabled()
            compose.onNodeWithTag("chat-model-picker-cancel").performClick()
            compose.onNodeWithTag("chat-model-list").assertDoesNotExist()
            compose.onNodeWithTag("chat-input").assertTextEquals("Keep this unsent draft")
        }
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun switchingSessionsClosesTheOpenModelPickerWithoutChangingEitherDraft() {
        render()
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-list").assertIsDisplayed()
        compose.runOnIdle {
            screen.value = conversationScreenFixture("another-session")
            input.value = "Other session draft"
        }
        compose.onNodeWithTag("chat-model-list").assertDoesNotExist()
        compose.onNodeWithTag("chat-input").assertTextEquals("Other session draft")
        compose.runOnIdle {
            screen.value = conversationScreenFixture()
            input.value = "Keep this unsent draft"
        }
        compose.onNodeWithTag("chat-model-list").assertDoesNotExist()
        compose.onNodeWithTag("chat-input").assertTextEquals("Keep this unsent draft")
        compose.runOnIdle { assertEquals(0, sends) }
    }

    private fun render() {
        compose.setContent {
            MaterialTheme {
                ChatScreen(
                    screen = screen.value,
                    runControl = RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT),
                    input = input.value,
                    onInput = { input.value = it },
                    bindableProviders = emptyList(),
                    intents = conversationIntentsFixture(onSend = { sends++ }, onNew = { newSessions++ }),
                    composerAvailability = ComposerAvailability(modelSelected = false),
                )
            }
        }
    }
}

internal fun conversationScreenFixture(sessionId: String = "session") =
    ChatScreenState(
        sessions = emptyList(),
        openSessionId = sessionId,
        selectedModel = null,
        messages = emptyList(),
        toolTimeline = emptyList(),
        activeTurn = null,
        pendingDisclosure = null,
        blockedReason = null,
        retryTargetTurnId = null,
        isDraft = true,
    )

internal fun conversationIntentsFixture(
    onSend: () -> Unit = {},
    onNew: () -> Unit = {},
) = ConversationIntents(
    onSend = onSend,
    onStop = {},
    onDismissBlocked = {},
    onApproveApproval = {},
    onDenyApproval = {},
    onStageAttachment = {},
    onRemoveAttachment = {},
    onSetMode = {},
    onNew = onNew,
)
