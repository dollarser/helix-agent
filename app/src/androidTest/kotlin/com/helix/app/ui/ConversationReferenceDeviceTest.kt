package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class ConversationReferenceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun composerReferencePickerSelectsAnotherConversationAndShowsRemovableChip() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val source = requireNotNull(chat.screen.value.openSessionId)
            assertEquals(source, chat.materializeDraftSession(source))
            container.storage.withTransaction {
                container.storage.messages.append(
                    "reference-source-message",
                    source,
                    null,
                    "USER",
                    "TEXT",
                    "Reusable source context",
                )
            }

            chat.newSessionDraft()
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                chat.screen.value.isDraft &&
                    chat.screen.value.openSessionId != null &&
                    chat.screen.value.openSessionId != source
            }
            val target = requireNotNull(chat.screen.value.openSessionId)
            assertNotEquals(source, target)

            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("chat-add").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("chat-add").performClick()
            compose.onNodeWithTag("composer-add-reference").performClick()
            compose.onNodeWithTag("conversation-reference-sheet").assertIsDisplayed()
            compose.onNodeWithTag("conversation-reference-$source").performClick()

            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("composer-reference-chip").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("composer-reference-chip").assertIsDisplayed()
            compose.onNodeWithTag("composer-reference-remove").performClick()
            compose.onNodeWithTag("composer-reference-chip").assertDoesNotExist()
        }
}
