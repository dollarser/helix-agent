package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import com.helix.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FileConversationHandoffDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun currentAndNewDestinationsStageWithoutSendingOrChangingOriginal() {
        compose.resetDeterministicUiState()
        val chat = compose.container().chatService
        var current: String? = null
        compose.runOnIdle { current = chat.newSessionDraft() }
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == current }
        val file = compose.activity.filesDir.resolve("workspaces/app/work/handoff-${UUID.randomUUID()}.txt")
        file.parentFile?.mkdirs()
        file.writeText("Original input")
        try {
            val uri =
                FileProvider.getUriForFile(
                    compose.activity,
                    "${compose.activity.packageName}.fileprovider",
                    file,
                    "资料.txt",
                )
            var opened: String? = null
            val handoff = FileConversationHandoff(chat) { opened = it }
            compose.runOnIdle { assertTrue(handoff.attach(uri.toString(), current, false)) }
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                chat.screen.value.pendingAttachments
                    .isNotEmpty()
            }
            assertEquals(current, opened)
            assertEquals(
                "资料.txt",
                chat.screen.value.pendingAttachments
                    .single()
                    .fileName,
            )
            assertTrue(
                chat.screen.value.messages
                    .isEmpty(),
            )
            assertNull(chat.screen.value.activeTurn)
            compose.runOnIdle { assertTrue(handoff.attach(uri.toString(), current, true)) }
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                chat.screen.value.openSessionId == opened &&
                    chat.screen.value.pendingAttachments
                        .isNotEmpty()
            }
            assertNotEquals(current, opened)
            assertTrue(chat.screen.value.isDraft)
            assertNull(chat.screen.value.activeTurn)
            assertEquals("Original input", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test fun unavailableDirectoryKeepsTheOriginalConversation() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val chat = compose.container().chatService
            var original: String? = null
            compose.runOnIdle { original = chat.newSessionDraft() }
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == original }
            val failure = runCatching { chat.newDirectoryDraft("scope:ws-missing:work", original) }
            assertTrue(failure.isFailure)
            assertEquals(original, chat.screen.value.openSessionId)
            assertNull(chat.screen.value.directoryRef)
        }

    @Test fun staleDestinationCannotAttachToAnotherConversation() {
        compose.resetDeterministicUiState()
        val chat = compose.container().chatService
        var original: String? = null
        var replacement: String? = null
        compose.runOnIdle { original = chat.newSessionDraft() }
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == original }
        var navigated = false
        val handoff = FileConversationHandoff(chat) { navigated = true }
        compose.runOnIdle {
            replacement = chat.newSessionDraft()
            assertFalse(handoff.attach("content://unavailable", original, false))
            assertFalse(handoff.attach("content://unavailable", original, true))
        }
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == replacement }
        assertFalse(navigated)
        assertEquals(replacement, chat.screen.value.openSessionId)
        assertTrue(
            chat.screen.value.pendingAttachments
                .isEmpty(),
        )
    }
}
