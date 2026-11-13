package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import com.helix.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FileConversationHandoffDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun currentAndNewDestinationsStageWithoutSendingOrChangingOriginal() =
        runBlocking<Unit> {
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
                assertTrue(handoff.attach(uri.toString(), current, false))
                awaitRetainedAttachment(chat, requireNotNull(current), file)
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
                val beforeHandoff = handoffState(chat, requireNotNull(current))
                val attachedToNew = handoff.attach(uri.toString(), current, true)
                assertTrue(
                    "New handoff rejected; before=$beforeHandoff; after=${handoffState(chat, requireNotNull(current))}",
                    attachedToNew,
                )
                awaitRetainedAttachment(chat, requireNotNull(opened), file)
                assertNotEquals(current, opened)
                assertFalse(chat.screen.value.isDraft)
                assertTrue(
                    chat.screen.value.messages
                        .isEmpty(),
                )
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

    @Test fun staleDestinationCannotAttachToAnotherConversation() =
        runBlocking<Unit> {
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
            }
            assertFalse(handoff.attach("content://unavailable", original, false))
            assertFalse(handoff.attach("content://unavailable", original, true))
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { chat.screen.value.openSessionId == replacement }
            assertFalse(handoff.attach("content://unavailable", replacement, false) { false })
            assertFalse(handoff.attach("content://unavailable", replacement, true) { false })
            assertFalse(navigated)
            assertEquals(replacement, chat.screen.value.openSessionId)
            assertTrue(
                chat.screen.value.pendingAttachments
                    .isEmpty(),
            )
        }

    /** A visible metadata row precedes automatic retention/import; never race its preparation owner. */
    private fun awaitRetainedAttachment(
        chat: com.helix.feature.conversation.application.ChatService,
        sessionId: String,
        original: java.io.File,
    ) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            val screen = chat.screen.value
            chat.isCurrentConversation(sessionId) && screen.openSessionId == sessionId &&
                !screen.isDraft && !draftInput(chat).drafts.preparing && screen.pendingAttachments.size == 1
        }
        val storage = compose.container().storage
        assertNotNull(storage.sessions.find(sessionId))
        val pending =
            chat.screen.value.pendingAttachments
                .single()
        val artifact = storage.artifacts.listBySession(sessionId).single { it.id == pending.id }
        assertEquals(original.length(), artifact.size)
        assertEquals(
            com.helix.core.storage.content.FileContentStore
                .sha256Hex(original),
            artifact.sha256,
        )
        assertTrue(storage.turns.listBySession(sessionId).isEmpty())
    }

    private fun draftInput(
        chat: com.helix.feature.conversation.application.ChatService,
    ): com.helix.feature.conversation.application.ComposerInputState {
        val field = chat.javaClass.getDeclaredField("inputState").apply { isAccessible = true }
        return field.get(chat) as com.helix.feature.conversation.application.ComposerInputState
    }

    private fun handoffState(
        chat: com.helix.feature.conversation.application.ChatService,
        expected: String,
    ): String {
        val input = draftInput(chat)
        return "current=${chat.isCurrentConversation(expected)},draft=${chat.screen.value.isDraft}," +
            "preparing=${input.drafts.preparing},attachments=${chat.screen.value.pendingAttachments.size}"
    }
}
