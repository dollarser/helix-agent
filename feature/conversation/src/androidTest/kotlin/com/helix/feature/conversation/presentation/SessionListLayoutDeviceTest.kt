package com.helix.feature.conversation.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.ui.shared.UiLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SessionListLayoutDeviceTest : ConversationDeviceTestFixture() {
    @get:Rule val compose = createComposeRule()

    @Test fun shortTitleKeepsTimeReadable() = verifyLayout("Short title", 360.dp, 1f)

    @Test fun longEnglishTitleKeepsTimeReadableOnNarrowScreen() =
        verifyLayout("Synthetic closed loop mobile-boundary-main-return-max-output-20261117".repeat(3), 280.dp, 1f)

    @Test fun longChineseTitleKeepsTimeReadableWithLargeFont() =
        verifyLayout("这是一个很长的会话名称用于验证窄屏大字体时间显示".repeat(3), 280.dp, 2f)

    @Test fun unbrokenEnglishTitleKeepsTimeReadableWithLargeFont() =
        verifyLayout("LongUnbrokenConversationTitle".repeat(6), 280.dp, 2f)

    @Test fun archivedRowKeepsTimeAndRestoreActionReadable() =
        verifyLayout(
            "归档会话标题".repeat(20),
            280.dp,
            2f,
            archived = true,
        )

    private fun verifyLayout(
        title: String,
        width: Dp,
        fontScale: Float,
        archived: Boolean = false,
    ) {
        val session = SessionRowUi("fixture", title, 1_800_000_000_000, archived, "Helix", "model")
        var opened: String? = null
        var action: String? = null
        showSession(session, width, fontScale, { opened = it }, { action = it })
        if (archived) compose.onNodeWithTag("chat-archive-list-toggle").performClick()
        val titleNode = compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
        val timeNode =
            compose
                .onNodeWithText(UiLabels.formatTime(session.createdAt), useUnmergedTree = true)
                .assertIsDisplayed()
        assertCompleteSingleLine(timeNode.textLayout())
        val titleLayout = titleNode.textLayout()
        assertTrue(titleLayout.lineCount <= 2)
        if (title.length > 100) assertTrue(titleLayout.isLineEllipsized(titleLayout.lineCount - 1))
        val titleBounds = titleNode.getUnclippedBoundsInRoot()
        val timeBounds = timeNode.getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("chat-session-fixture")
        val rowBounds = row.getUnclippedBoundsInRoot()
        assertTrue(titleBounds.right - titleBounds.left > 0.dp)
        assertTrue(titleBounds.right + 8.dp <= timeBounds.left)
        assertTrue(timeBounds.right <= rowBounds.right)
        assertEquals(titleBounds.top, timeBounds.top)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actionLabel =
            context.getString(
                if (archived) {
                    com.helix.feature.conversation.R.string.chat_restore
                } else {
                    com.helix.feature.conversation.R.string.chat_archive
                },
            )
        val actionText = compose.onNodeWithText(actionLabel, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, actionText.textLayout().lineCount)
        assertTrue(actionText.getUnclippedBoundsInRoot().right <= rowBounds.right)
        screenshot("${title.length}-$fontScale-$archived")
        titleNode.performClick()
        assertEquals(session.id, opened)
        compose.onNodeWithTag(if (archived) "chat-restore" else "chat-archive").assertIsDisplayed().performClick()
        assertEquals(session.id, action)
    }

    private fun showSession(
        session: SessionRowUi,
        width: Dp,
        fontScale: Float,
        onOpen: (String) -> Unit,
        onAction: (String) -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(width)) {
                        SessionListSection(
                            sessions = listOf(session),
                            search = SessionSearchUiState(),
                            onNew = {},
                            onOpen = onOpen,
                            onArchive = onAction,
                            onRestore = onAction,
                            onTasks = {},
                            onRename = {},
                            onNavigation = {},
                            showHeader = false,
                        )
                    }
                }
            }
        }
    }

    private fun assertCompleteSingleLine(layout: TextLayoutResult) {
        // With softWrap=false, the paragraph can be wider than the Text even when every glyph fits.
        assertEquals(1, layout.lineCount)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
        assertTrue(layout.getLineRight(0) <= layout.size.width + 1f)
        assertTrue(layout.getLineBottom(0) <= layout.size.height + 1f)
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "session-list-layout").also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }
}
