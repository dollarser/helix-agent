package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.MainActivity
import com.helix.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationTopBarDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun conversationOwnsTheTopAndNavigationRemainsReachable() {
        compose.resetDeterministicUiState()
        val chat = compose.container().chatService
        chat.closeSession()
        compose.waitUntil { chat.screen.value.openSessionId == null }
        compose.onNodeWithTag("shell-top-bar").assertDoesNotExist()
        compose.onNodeWithTag("chat-new-session").performClick()
        compose.waitUntil { chat.screen.value.isDraft }
        compose.onNodeWithTag("chat-header").assertIsDisplayed()
        compose.onNodeWithTag("shell-top-bar").assertDoesNotExist()
        val page = compose.onNodeWithTag("screen-sessions").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("chat-header").getUnclippedBoundsInRoot()
        assertEquals("No extra title or controls above the conversation header", page.top, header.top)
        compose.onNodeWithTag("open-navigation").assertIsDisplayed()
        compose.navigateTo("settings")
        compose.onNodeWithTag("shell-top-bar").assertIsDisplayed()
        compose.navigateTo("sessions")
        compose.onNodeWithTag("chat-header").assertIsDisplayed()
        compose.onNodeWithTag("chat-back").performClick()
        compose.waitUntil { chat.screen.value.openSessionId == null }
        compose.onNodeWithTag("chat-session-list").assertIsDisplayed()
        compose.onNodeWithTag("open-navigation").assertIsDisplayed()
        compose.onNodeWithTag("chat-new-session").performClick()
        compose.waitUntil { chat.screen.value.isDraft }
        compose.onNodeWithTag("chat-new-session").performClick()
        compose.onNodeWithTag("chat-header").assertIsDisplayed()
        chat.closeSession()
    }

    @Test fun destinationHeadersAndRealExtensionsAreReachable() {
        compose.resetDeterministicUiState()
        compose.onNodeWithTag("chat-new-session").performClick()
        compose.waitUntil {
            compose
                .container()
                .chatService.screen.value.isDraft
        }
        val chatBounds = compose.onNodeWithTag("chat-header").getUnclippedBoundsInRoot()
        val height = chatBounds.bottom - chatBounds.top
        listOf("files", "browser", "extensions", "permissions", "settings", "audit").forEach { route ->
            compose.navigateTo(route)
            compose.onNodeWithTag("open-navigation").assertIsDisplayed()
            val bounds = compose.onNodeWithTag("shell-top-bar").getUnclippedBoundsInRoot()
            assertEquals(height, bounds.bottom - bounds.top)
        }
        compose.navigateTo("extensions")
        compose.onNodeWithTag("skill-creator-open").performScrollTo().performClick()
        compose.onNodeWithTag("skill-creator-name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("skill-creator-open").performScrollTo().performClick()
        compose.onNodeWithTag("skill-installer-path").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connector-paste").performScrollTo().performClick()
        compose.onNodeWithTag("connector-json").performScrollTo().assertIsDisplayed()
        compose.navigateTo("sessions")
        compose.container().chatService.closeSession()
    }

    @Test fun archivedSessionIsRetainedInItsOwnList(): Unit =
        runBlocking {
            compose.resetDeterministicUiState()
            val chat = compose.container().chatService
            chat.newSessionDraft()
            compose.waitUntil { chat.screen.value.isDraft }
            val id = requireNotNull(chat.screen.value.openSessionId)
            assertTrue(chat.saveDraftForGoal("Archive label fixture"))
            val originalTitle =
                compose
                    .container()
                    .storage.sessions
                    .resolve(id)
                    .title
            chat.closeSession()
            chat.archiveSession(id)
            compose.waitUntil { chat.sessions.value.any { it.id == id && it.isArchived } }
            compose.onNodeWithTag("chat-session-$id").assertDoesNotExist()
            compose.onNodeWithTag("chat-archive-list-toggle").performClick()
            assertTrue(
                compose
                    .container()
                    .storage.sessions
                    .resolve(id)
                    .archivedAt != null,
            )
            compose
                .onNode(hasTestTag("chat-restore") and hasAnyAncestor(hasTestTag("chat-session-$id")))
                .performScrollTo()
                .performClick()
            compose.waitUntil { chat.sessions.value.any { it.id == id && !it.isArchived } }
            compose.onNodeWithTag("chat-session-$id").assertDoesNotExist()
            compose.onNodeWithTag("chat-archive-list-toggle").performClick()
            compose.onNodeWithTag("chat-session-$id").performScrollTo().assertIsDisplayed()
            assertEquals(
                originalTitle,
                compose
                    .container()
                    .storage.sessions
                    .resolve(id)
                    .title,
            )
            chat.archiveSession(id)
        }

    @Test fun draftTitleCanBeEditedWithoutSavingAnEmptySession() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            chat.newSessionDraft()
            compose.waitUntil { chat.screen.value.isDraft }
            val id = requireNotNull(chat.screen.value.openSessionId)
            compose.onNodeWithTag("chat-title").performClick()
            compose.onNodeWithTag("session-title").performTextReplacement("My chosen title")
            compose.onNodeWithTag("session-rename-save").performClick()
            compose.waitUntil { chat.screen.value.sessionTitle == "My chosen title" }
            assertTrue(storage.sessions.list().none { it.id == id })
            try {
                assertTrue(chat.saveDraftForGoal("First message should not replace my title"))
                assertEquals("My chosen title", storage.sessions.resolve(id).title)
                chat.closeSession()
                chat.openSession(id)
                compose.waitUntil { chat.screen.value.sessionTitle == "My chosen title" }
            } finally {
                chat.closeSession()
                storage.sessions.archive(id, System.currentTimeMillis())
            }
        }
}
