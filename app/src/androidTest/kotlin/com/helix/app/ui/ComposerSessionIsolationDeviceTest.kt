package com.helix.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.MainActivity
import com.helix.core.model.SessionPermissionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real composer, session permission service and Room; no provider or external request. */
class ComposerSessionIsolationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun draftsAndPermissionsStayWithTheirSessionsAcrossSwitchAndActivityRecreation() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val permissions = container.sessionPermissionEdit
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            listOf(first, second).forEach {
                container.storage.sessions.create(it, "Composer isolation", null, null, System.currentTimeMillis())
            }
            try {
                chat.openSession(first)
                compose.waitUntil(10000) { chat.screen.value.openSessionId == first }
                compose.onNodeWithTag("chat-input").performTextReplacement("draft A")
                selectPermission(SessionPermissionMode.READ_ONLY)
                compose.waitUntil(10000) {
                    permissions.activeConfigFor(first)?.mode == SessionPermissionMode.READ_ONLY &&
                        runBlocking { chat.loadComposerDraft(first)?.text == "draft A" }
                }
                chat.openSession(second)
                compose.waitUntil(10000) { chat.screen.value.openSessionId == second }
                compose.onNodeWithTag("chat-input").assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
                )
                compose.onNodeWithTag("chat-input").performTextReplacement("draft B")
                selectPermission(SessionPermissionMode.APPROVAL_REQUIRED)
                compose.waitUntil(10000) {
                    val mode = permissions.activeConfigFor(second)?.mode
                    mode == SessionPermissionMode.APPROVAL_REQUIRED &&
                        runBlocking { chat.loadComposerDraft(second)?.text == "draft B" }
                }
                chat.openSession(first)
                compose.waitUntil(10000) { chat.screen.value.openSessionId == first }
                compose.onNodeWithTag("chat-input").assertTextEquals("draft A")
                compose.runOnUiThread { compose.activity.recreate() }
                compose.waitForIdle()
                compose.onNodeWithTag("chat-input").assertTextEquals("draft A")
                assertEquals(SessionPermissionMode.READ_ONLY, permissions.activeConfigFor(first)?.mode)
                chat.openSession(second)
                compose.waitUntil(10000) { chat.screen.value.openSessionId == second }
                compose.onNodeWithTag("chat-input").assertTextEquals("draft B")
                assertEquals(
                    SessionPermissionMode.APPROVAL_REQUIRED,
                    permissions.activeConfigFor(second)?.mode,
                )
                assertNoSubmissions(listOf(first, second))
            } finally {
                chat.closeSession()
                listOf(first, second).forEach { container.storage.deleteSessionPermanently(it) }
            }
        }

    private fun selectPermission(mode: SessionPermissionMode) {
        compose.onNodeWithTag("chat-permission-menu").performClick()
        compose.onNodeWithTag("chat-permission-${mode.name}").performClick()
    }

    private fun assertNoSubmissions(sessions: List<String>) {
        val storage = compose.container().storage
        sessions.forEach {
            assertTrue(storage.messages.listBySession(it).isEmpty())
            assertTrue(storage.turns.listBySession(it).isEmpty())
        }
    }
}
