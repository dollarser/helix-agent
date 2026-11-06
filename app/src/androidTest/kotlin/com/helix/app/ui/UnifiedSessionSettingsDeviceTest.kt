package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.core.model.AgentMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Production navigation and owned Room state, without external services or permission grants. */
class UnifiedSessionSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun stableEmptyOutputsAndCompactSettingsKeepDraftAndNavigateInOneStep() {
        compose.resetDeterministicUiState()
        val c = compose.container()
        val chat = c.chatService
        val id = requireNotNull(chat.screen.value.openSessionId)
        val default = c.sessionPermissionEdit.appDefault()
        try {
            compose.onNodeWithTag("chat-artifacts-open").assertDoesNotExist()
            compose.onNodeWithTag("chat-conversation-details").performClick()
            compose.onNodeWithTag("chat-artifacts-open").assertIsDisplayed().performClick()
            compose.waitUntil(
                10_000,
            ) { compose.onAllNodesWithTag("chat-artifacts-empty").fetchSemanticsNodes().isNotEmpty() }
            compose
                .onNodeWithText(
                    compose.activity.getString(com.helix.app.R.string.chat_artifacts_empty),
                ).assertIsDisplayed()
            assertNull(c.storage.sessions.find(id))
            screenshot("empty-outputs")
            compose.onNodeWithTag("chat-artifacts-close").performClick()
            compose.onNodeWithTag("chat-conversation-details").performClick()
            compose.onNodeWithTag("session-settings-open").performClick()
            assertSecondaryDetailsCollapsed()
            compose.onNodeWithTag("session-settings-mode").performScrollTo().performClick()
            compose.onNodeWithTag("session-mode-PLAN").performClick()
            compose.waitUntil(10_000) { chat.runControl.value.mode == AgentMode.PLAN }
            awaitSettingsFocus()
            assertTrue(chat.screen.value.isDraft)
            assertNull(c.storage.sessions.find(id))
            compose.onNodeWithTag("session-settings-permissions").performScrollTo().performClick()
            compose.waitUntil(
                10_000,
            ) { compose.onAllNodesWithTag("session-permissions-sheet").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(id, chat.screen.value.openSessionId)
            assertTrue(!chat.screen.value.isDraft)
            compose.onNodeWithTag("settings-perm-mode-APPROVAL_REQUIRED").performScrollTo().assertIsDisplayed()
            screenshot("permission-editor")
            compose.onNodeWithTag("session-permissions-close").performClick()
            awaitSettingsFocus()
            compose.onNodeWithTag("session-settings-directory").performScrollTo().performClick()
            compose.onNodeWithTag("session-directory-details").performClick()
            compose
                .onNodeWithText(
                    compose.activity.getString(com.helix.app.R.string.workspace_copy_location),
                ).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(com.helix.app.R.string.chat_details_close)).performClick()
            awaitSettingsFocus()
            assertEquals(default, c.sessionPermissionEdit.appDefault())
            compose.onNodeWithTag("settings-perm-mode-APPROVAL_REQUIRED").assertDoesNotExist()
            compose.onNodeWithTag("session-settings-mode").performScrollTo()
            assertOwnedDirectoryLabel(id)
            screenshot("settings-summary")
        } catch (failure: Throwable) {
            throw failureWithState(id, failure)
        } finally {
            chat.closeSession()
            if (c.storage.sessions.find(id) != null) c.storage.deleteSessionPermanently(id)
        }
    }

    @Test fun unavailableDirectoryKeepsDraftAndShowsFailedPreparationAtTheClickedSettings() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val c = compose.container()
            val chat = c.chatService
            val projects = requireNotNull(c.projects)
            val project = projects.save(null, "Settings boundary ${UUID.randomUUID()}", "", "", null)
            val row = requireNotNull(c.storage.projects.find(project))
            val id = requireNotNull(chat.newProjectDraft(project, chat.screen.value.openSessionId))
            try {
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == id }
                c.storage.workspaces.markUnavailable(row.workspaceId)
                compose.onNodeWithTag("chat-conversation-details").performClick()
                compose.onNodeWithTag("session-settings-open").performClick()
                compose.onNodeWithTag("session-settings-permissions").performScrollTo().performClick()
                compose.waitUntil(
                    10_000,
                ) { compose.onAllNodesWithTag("session-settings-error").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("session-settings-error").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("session-permissions-sheet").assertDoesNotExist()
                assertTrue(chat.screen.value.isDraft)
                assertNull(c.storage.sessions.find(id))
                assertEquals(project, chat.screen.value.projectId)
                screenshot("preparation-failed")
            } finally {
                chat.closeSession()
                c.storage.projects
                    .find(project)
                    ?.let { projects.delete(it) }
            }
        }

    private fun assertOwnedDirectoryLabel(id: String) {
        val c = compose.container()
        val directory = requireNotNull(c.chatService.screen.value.directoryRef)
        val scope =
            com.helix.core.workspace.FileScopePath
                .fromModelReference(directory)
                .scopeId
        val workspace = c.storage.workspaces.find(scope)
        val source = c.fileManager.sources().singleOrNull { it.scopeId == scope }
        assertEquals(id, workspace?.ownerSessionId)
        assertEquals(id, source?.ownerSessionId)
        val label = compose.activity.getString(com.helix.app.R.string.workspace_session_directory)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(scope).assertDoesNotExist()
    }

    private fun assertSecondaryDetailsCollapsed() {
        listOf(
            "session-settings-manage-models",
            "session-settings-expert",
            "session-settings-connectors",
            "chat-mode-explanation",
            "directory-access-capability",
            "settings-perm-mode-APPROVAL_REQUIRED",
        ).forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    private fun failureWithState(
        id: String,
        cause: Throwable,
    ): AssertionError {
        screenshot("unexpected-failure")
        val c = compose.container()
        val current = c.chatService.screen.value
        val disabled =
            compose
                .onAllNodesWithTag("session-settings-permissions")
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        return AssertionError(
            "Settings failure: expected=$id open=${current.openSessionId} draft=${current.isDraft} " +
                "stored=${c.storage.sessions.find(id) != null} blocked=${current.blockedReason} disabled=$disabled",
            cause,
        )
    }

    private fun awaitSettingsFocus() {
        compose.waitUntil(10_000) {
            var focused = false
            compose.runOnUiThread { focused = compose.activity.hasWindowFocus() }
            focused
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(350, 5_000)
        val file = File(compose.activity.filesDir, "unified-settings/$name.png")
        file.parentFile?.mkdirs()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
