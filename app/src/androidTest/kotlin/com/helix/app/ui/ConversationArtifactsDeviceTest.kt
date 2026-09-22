package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.MainActivity
import com.helix.app.chat.ArtifactQuery
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ConversationArtifactsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun previewPreservesDraftAndRechecksChangedAndMissingFiles() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val session = newSession()
            val (id, file) = artifact(session)
            openSession(session)
            compose.onNodeWithTag("chat-input").performTextReplacement("Continue after preview")
            openPreview(id)
            awaitTag("artifact-file-preview")
            compose
                .onNodeWithTag(
                    "artifact-file-preview",
                    true,
                ).assertTextContains("Delivered report", substring = true)
            compose.waitForIdle()
            captureChatLayout(compose.activity, "refactor-artifact-preview")
            compose.onNodeWithTag("chat-artifacts-back").performClick()
            compose.onNodeWithTag("chat-artifacts-list").assertIsDisplayed()
            compose.onNodeWithTag("chat-artifacts-close").performClick()
            compose.onNodeWithTag("chat-input").assertTextContains("Continue after preview")

            file.writeText("Changed report")
            openPreview(id)
            awaitTag("artifact-file-changed")
            compose.onNodeWithTag("chat-artifacts-close").performClick()
            assertTrue(file.delete())
            openPreview(id)
            awaitTag("artifact-file-missing")
            compose.onNodeWithTag("chat-artifacts-close").performClick()
            compose.onNodeWithTag("chat-input").assertTextContains("Continue after preview")
            assertEquals(
                session,
                compose
                    .container()
                    .chatService.screen.value.openSessionId,
            )
            assertTrue(
                compose
                    .container()
                    .storage.turns
                    .listBySession(session)
                    .isEmpty(),
            )
        }

    @Test fun sessionQueryIsNotTruncatedByOtherSessionsAndExcludesInputs() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val session = newSession()
            val (outputId, _) = artifact(session)
            artifact(session, turnId = null)
            val other = newSession()
            repeat(4) { artifact(other) }
            val query = ArtifactQuery(compose.container().storage)
            assertTrue(query.recent(3).none { it.sessionId == session })
            assertEquals(listOf(outputId), query.forSession(session).map { it.id })
            assertEquals(4, query.forSession(other).size)
            openSession(session)
            compose.onNodeWithTag("chat-artifacts-open").assertTextContains("(1)", substring = true)
            openPreview(outputId)
            awaitTag("artifact-file-preview")
            // Switching a session while a read-only sheet is open cannot retain its selection.
            compose.container().chatService.openSession(other)
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose
                    .container()
                    .chatService.screen.value.openSessionId == other
            }
            compose.onNodeWithTag("chat-artifacts-sheet").assertDoesNotExist()
            awaitTag("chat-artifacts-open")
            compose.onNodeWithTag("chat-artifacts-open").assertTextContains("(4)", substring = true)
        }

    @Test fun inputOnlySessionDoesNotAdvertiseOutputs() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val session = newSession()
            artifact(session, turnId = null)
            openSession(session, outputs = false)
            assertTrue(
                compose
                    .container()
                    .chatService
                    .conversationArtifacts(session)
                    .isEmpty(),
            )
            compose.onNodeWithTag("chat-artifacts-open").assertDoesNotExist()
        }

    private fun newSession(): String {
        val id = "preview-${UUID.randomUUID()}"
        compose
            .container()
            .storage.sessions
            .create(id, "Preview fixture", null, null, 1000)
        return id
    }

    @Test fun unavailableGrantShowsRevokedWithoutFallingBackToWorkspace() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val session = newSession()
            val (id, file) = artifact(session, scopeId = "saf-unavailable")
            assertTrue(file.exists())
            openSession(session)
            openPreview(id)
            awaitTag("artifact-file-revoked")
            compose.onNodeWithTag("artifact-file-preview", true).assertDoesNotExist()
            compose.onNodeWithTag("chat-artifacts-close").performClick()
            compose.onNodeWithTag("chat-header").assertIsDisplayed()
        }

    @Test fun longListCanReachAnOlderImageAndReturnToConversation() =
        runBlocking<Unit> {
            compose.resetDeterministicUiState()
            val session = newSession()
            val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val encoded = ByteArrayOutputStream()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
            bitmap.recycle()
            val (id, _) = artifact(session, bytes = encoded.toByteArray(), name = "preview.png", mime = "image/png")
            repeat(24) { artifact(session) }
            openSession(session)
            compose.onNodeWithTag("chat-artifacts-open").performClick()
            compose.onNodeWithTag("chat-artifacts-list").performScrollToNode(hasTestTag("artifact-file-row-$id"))
            compose.onNodeWithTag("artifact-file-row-$id").performClick()
            awaitTag("artifact-file-image")
            compose.onNodeWithTag("chat-artifacts-back").performClick()
            compose.onNodeWithTag("artifact-file-row-$id").assertIsDisplayed()
            compose.onNodeWithTag("chat-artifacts-close").performClick()
            compose.onNodeWithTag("chat-header").assertIsDisplayed()
        }

    private fun artifact(
        session: String,
        turnId: String? = "turn-${UUID.randomUUID()}",
        bytes: ByteArray = "Delivered report\nReady for review".toByteArray(),
        name: String = "report.txt",
        mime: String = "text/plain",
        scopeId: String = "app",
    ): Pair<String, File> {
        val id = "file-${UUID.randomUUID()}"
        val relativePath = "output/$id/$name"
        val file = File(compose.activity.filesDir, "workspaces/app/$relativePath")
        requireNotNull(file.parentFile).mkdirs()
        file.writeBytes(bytes)
        compose.container().storage.artifacts.registerOrRefresh(
            id,
            session,
            FileScopePath(scopeId, relativePath).toModelReference(),
            mime,
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            turnId,
            file,
        )
        return id to file
    }

    private fun openSession(
        session: String,
        outputs: Boolean = true,
    ) {
        compose.navigateTo("sessions")
        compose.container().chatService.openSession(session)
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose
                .container()
                .chatService.screen.value.openSessionId ==
                session
        }
        compose.onNodeWithTag("chat-header").assertIsDisplayed()
        if (outputs) awaitTag("chat-artifacts-open")
    }

    private fun openPreview(id: String) {
        compose.onNodeWithTag("chat-artifacts-open").performClick()
        compose.onNodeWithTag("artifact-file-row-$id").performClick()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag, true).assertIsDisplayed()
    }
}
