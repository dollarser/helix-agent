package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
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
                    "reference-source-message-${java.util.UUID.randomUUID()}",
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
            // Add a source after the open conversation projection was produced. The picker must
            // refresh/read the directory itself rather than a stale ChatScreenState.sessions copy.
            val (lateSource, sameTitleSource) = prepareChoices(target)

            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("chat-add").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("chat-add").performClick()
            compose.onNodeWithTag("composer-add-reference").performClick()
            compose.onNodeWithTag("conversation-reference-sheet").assertIsDisplayed()
            compose.onNodeWithTag("reference-search").performClick().performTextInput("Late reference source")
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("conversation-reference-$lateSource").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("conversation-reference-$sameTitleSource").assertIsNotEnabled()
            compose.onNodeWithTag("conversation-reference-$target").assertDoesNotExist()
            Espresso.pressBack() // First back hides the IME without losing the picker/search.
            compose.onNodeWithTag("conversation-reference-sheet").assertIsDisplayed()
            compose.onNodeWithTag("conversation-reference-$lateSource").performClick()

            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("composer-reference-chip").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("composer-reference-chip").assertIsDisplayed()
            compose.onNodeWithText("Late reference source", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("chat-add").performClick()
            compose.onNodeWithTag("composer-add-reference").performClick()
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose.onAllNodesWithTag("conversation-reference-$lateSource").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("conversation-reference-$lateSource").assertIsSelected()
            compose.onNodeWithTag("conversation-reference-close").performClick()
            compose.onNodeWithTag("composer-reference-remove").performClick()
            compose.onNodeWithTag("composer-reference-chip").assertDoesNotExist()
        }

    private suspend fun prepareChoices(target: String): Pair<String, String> {
        val container = compose.container()
        val lateSource = "late-reference-${java.util.UUID.randomUUID()}"
        container.storage.sessions.create(lateSource, "Late reference source", null, null, 1)
        container.storage.messages.append(
            "late-message-${java.util.UUID.randomUUID()}",
            lateSource,
            null,
            "USER",
            "TEXT",
            "Late reusable context",
        )

        val sameTitleSource = "same-title-${java.util.UUID.randomUUID()}"
        container.storage.sessions.create(sameTitleSource, "Late reference source", null, null, 1)
        // Empty sessions remain visible but cannot produce a reference.
        repeat(24) { index ->
            container.storage.sessions.create("noise-$target-$index", "Other source $index", null, null, 1)
        }
        return lateSource to sameTitleSource
    }
}
