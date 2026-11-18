package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.ui.shared.UiLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Run on an owned offline emulator: real Room projection, navigation and application theme. */
class SessionHistoryLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun persistedEnglishHistoryKeepsTimeReadableAndDirectoryPrivate() = verifyHistory(false)

    @Test fun persistedChineseArchiveKeepsTimeReadableAndDirectoryPrivate() = verifyHistory(true)

    private fun verifyHistory(archived: Boolean) {
        check(InstrumentationRegistry.getArguments().getString("ownedSessionLayout") == "true")
        compose.resetDeterministicUiState()
        val container = compose.container()
        val id = "history-layout-${UUID.randomUUID()}"
        val title =
            if (archived) {
                "这是保留工作目录的历史会话长标题".repeat(9)
            } else {
                "Synthetic closed loop mobile-boundary-main-return-max-output-20261117 ".repeat(2).trim()
            }
        val createdAt = System.currentTimeMillis()
        val session = container.storage.sessions.create(id, title, null, "fixture-model", createdAt)
        assertNotNull(session.directoryRef)
        if (archived) container.storage.sessions.archive(id, createdAt + 1)
        compose.navigateTo(CONVERSATION_HISTORY_ROUTE)
        if (archived) compose.onNodeWithTag("chat-archive-list-toggle").performClick()
        val rowTag = "chat-session-$id"
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodes(hasTestTag(rowTag)).fetchSemanticsNodes().isNotEmpty()
        }
        val titleNode = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag(rowTag)), true).assertIsDisplayed()
        val time =
            compose
                .onNode(
                    hasText(UiLabels.formatTime(createdAt)) and hasAnyAncestor(hasTestTag(rowTag)),
                    true,
                ).assertIsDisplayed()
        val timeLayout = time.layout()
        assertEquals(1, timeLayout.lineCount)
        assertEquals(timeLayout.layoutInput.text.length, timeLayout.getLineEnd(0, visibleEnd = true))
        assertTrue(timeLayout.getLineRight(0) <= timeLayout.size.width + 1f)
        assertTrue(titleNode.layout().lineCount <= 2)
        val bounds = compose.onNodeWithTag(rowTag).getUnclippedBoundsInRoot()
        val timeBounds = time.getUnclippedBoundsInRoot()
        assertTrue(titleNode.getUnclippedBoundsInRoot().right + 8.dp <= timeBounds.left)
        assertTrue(timeBounds.right <= bounds.right)
        compose.onNode(hasText(requireNotNull(session.directoryRef)), true).assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            compose.activity.cacheDir.resolve("history-layout-$archived.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        titleNode.performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) { container.chatService.screen.value.openSessionId == id }
        val retained = container.storage.sessions.resolve(id)
        assertEquals(title, retained.title)
        assertEquals(session.directoryRef, retained.directoryRef)
        assertEquals(archived, retained.archivedAt != null)
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }
}
