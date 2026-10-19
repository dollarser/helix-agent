package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.MessageUi
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure UI state and synthetic messages: never changes the owner's sessions or preferences. */
class SessionForkConfirmationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val session = mutableStateOf("source")
    private val calls = mutableListOf<String>()
    private var messageId = "selected"

    private fun render(onFork: (String) -> Unit = { calls += it }) {
        compose.setContent {
            MaterialTheme {
                ConversationSection(
                    ChatScreenState(
                        emptyList(),
                        session.value,
                        null,
                        listOf(MessageUi(messageId, "assistant", "Selected synthetic answer")),
                        emptyList(),
                        null,
                        null,
                        null,
                        null,
                    ),
                    RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT),
                    "original draft",
                    {},
                    emptyList(),
                    ConversationIntents(
                        onSend = {},
                        onStop = {},
                        onDismissBlocked = {},
                        onApproveApproval = {},
                        onDenyApproval = {},
                        onStageAttachment = {},
                        onRemoveAttachment = {},
                        onSetMode = {},
                        onSetChatTools = {},
                        onFork = onFork,
                    ),
                )
            }
        }
        compose.onNodeWithTag("chat-message-toggle-$messageId").performClick()
    }

    @Test fun cancelKeepsHistoryUntouchedAndConfirmationNamesTheSource() {
        render()
        compose.onNodeWithTag("chat-fork-$messageId").performClick()
        compose.onNodeWithTag("session-fork-source").assertTextContains("Selected synthetic answer")
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
        compose.onNodeWithTag("session-fork-cancel").performClick()
        compose.onNodeWithTag("session-fork-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test fun confirmingTwiceDispatchesOnlyOnce() {
        render()
        compose.onNodeWithTag("chat-fork-$messageId").performClick()
        compose.onNodeWithTag("session-fork-confirm").performSemanticsAction(SemanticsActions.OnClick) { click ->
            click()
            click()
        }
        compose.onNodeWithTag("session-fork-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("selected"), calls) }
    }

    @Test fun switchingSessionsDismissesConfirmationEvenForTheSameMessageId() {
        render()
        compose.onNodeWithTag("chat-fork-$messageId").performClick()
        compose.runOnIdle { session.value = "other" }
        compose.onNodeWithTag("session-fork-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    // One synthetic persistence journey; records failures and cleans only its own sessions.
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    @Test
    fun confirmationCreatesOnePersistedBranchAndCancelCreatesNone() {
        val context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<com.helix.app.HelixApplication>()
        val container = context.appContainer
        val storage = container.storage
        val marker = "Branch confirmation ${java.util.UUID.randomUUID()}"
        val source =
            java.util.UUID
                .randomUUID()
                .toString()
        messageId =
            java.util.UUID
                .randomUUID()
                .toString()
        session.value = source
        val scope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val branch =
            java.util.concurrent.atomic
                .AtomicReference<String?>()
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
        storage.sessions.create(source, marker, null, null, System.currentTimeMillis())
        storage.messages.append(messageId, source, null, "ASSISTANT", "TEXT", "Selected synthetic answer")
        val baseline =
            storage.sessions
                .list()
                .map { it.id }
                .toSet()
        try {
            render { selected ->
                scope.launch {
                    try {
                        branch.set(container.chatService.forkSession(source, selected))
                    } catch (error: Exception) {
                        failure.set(error)
                    }
                }
            }
            compose.onNodeWithTag("chat-fork-$messageId").performClick()
            compose.onNodeWithTag("session-fork-cancel").performClick()
            assertEquals(
                baseline,
                storage.sessions
                    .list()
                    .map { it.id }
                    .toSet(),
            )
            compose.onNodeWithTag("chat-fork-$messageId").performClick()
            compose.onNodeWithTag("session-fork-confirm").performClick()
            compose.waitUntil(10000) { branch.get() != null || failure.get() != null }
            failure.get()?.let { throw AssertionError("Branch persistence failed", it) }
            val created = requireNotNull(branch.get())
            assertEquals(
                setOf(created),
                storage.sessions
                    .list()
                    .map { it.id }
                    .toSet() - baseline,
            )
            val rows = storage.messages.listBySession(created)
            assertEquals(listOf("TEXT", "SESSION_FORK"), rows.map { it.kind })
            assertEquals("Selected synthetic answer", storage.messages.readContent(rows.first()))
            assertEquals(1, storage.messages.listBySession(source).size)
        } finally {
            kotlinx.coroutines.runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            storage.sessions.list().filter { it.title.startsWith(marker) }.forEach {
                storage.deleteSessionPermanently(it.id)
            }
        }
    }
}
