package com.helix.app.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ComposerModelPlaceholderLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualEditorShowsHintThenDraftAndExistingModelMenuRemainsUsable() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("open-navigation").fetchSemanticsNodes().isNotEmpty()
        }
        val previous = c.chatService.screen.value.openSessionId
        val id = "model-placeholder-${UUID.randomUUID()}"
        c.storage.sessions.create(id, "Model placeholder validation", null, null, 0)
        try {
            c.chatService.openSession(id)
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                c.chatService.screen.value.openSessionId == id &&
                    compose
                        .onAllNodesWithTag(
                            "chat-input-placeholder",
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
            }
            compose
                .onNodeWithTag("chat-input-placeholder", useUnmergedTree = true)
                .assertTextEquals(compose.activity.getString(R.string.chat_input_placeholder))
                .assert(hasAnyAncestor(hasTestTag("chat-input")))
            compose.onNodeWithTag("chat-select-model-reminder").assertDoesNotExist()
            compose.onNodeWithTag("chat-context-window").assertIsDisplayed()
            captureChatLayout(compose.activity, "model-placeholder-empty")
            compose.onNodeWithTag("chat-context-window").performClick()
            compose.onNodeWithTag("chat-context-usage-details").assertIsDisplayed()
            compose.onNodeWithTag("chat-context-details-close").performClick()
            compose.onNodeWithTag("chat-composer-options").performClick()
            compose.onNodeWithTag("chat-context-details-open").assertDoesNotExist()
            captureChatLayout(compose.activity, "context-options")
            compose.onNodeWithTag("chat-composer-options-close").performClick()
            compose.onNodeWithTag("chat-input").performTextInput("保留这条草稿")
            compose.onNodeWithTag("chat-input").assertTextEquals("保留这条草稿")
            compose.onNodeWithTag("chat-context-window").assertIsDisplayed()
            compose.onNodeWithTag("chat-input-placeholder", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("chat-select-model-reminder").assertDoesNotExist()
            compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
            compose.onNodeWithTag("chat-input").assertTextEquals("保留这条草稿")
            compose.onNodeWithTag("chat-blocked-reason").assertDoesNotExist()
            compose.onNodeWithTag("chat-send-status").assertDoesNotExist()
            captureChatLayout(compose.activity, "model-send-reminder")
            captureChatLayout(compose.activity, "model-placeholder-draft")
            compose.onNodeWithTag("chat-model-menu").performClick()
            compose.onNodeWithTag("chat-model-search").assertIsDisplayed()
            compose.onNodeWithTag("chat-model-picker-cancel").performClick()
            compose.onNodeWithTag("chat-input").assertTextEquals("保留这条草稿")
            assertTrue(
                c.storage.turns
                    .listBySession(id)
                    .isEmpty(),
            )
        } finally {
            if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
            c.storage.deleteSessionPermanently(id)
        }
    }
}
