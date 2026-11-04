package com.helix.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilesBrowserUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("LongMethod") // One user journey binds creation, viewing and recovery to the same scoped file.
    @Test
    fun createsViewsCancelsAndRestoresFromHomeTrash() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
        val container = app.appContainer
        val folder = app.filesDir.resolve("workspaces/app/work/browser-ui-${System.nanoTime()}")
        check(folder.mkdirs())
        folder.resolve("note.txt").writeText("Document reader\nSecond line")
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        folder.resolve("picture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            compose.setContent {
                MaterialTheme {
                    FilesScreen(
                        container.fileManager,
                        container.safTree,
                        container.featureFiles,
                        initialDirectory = FileScopePath("app", "work/${folder.name}"),
                    )
                }
            }
            waitTag("files-entry-note.txt")
            compose.onNodeWithTag("files-favorite-directory").assertDoesNotExist()
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-favorite-directory").assertIsDisplayed()
            compose.onNodeWithTag("files-sort-NAME").assertDoesNotExist()
            compose.onNodeWithTag("files-sort-open").performClick()
            compose.onNodeWithTag("files-sort-TYPE").performScrollTo().performClick()
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-view-open").performClick()
            compose.onNodeWithTag("files-view-grid").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("files-entry-note.txt").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-view-open").performClick()
            compose.onNodeWithTag("files-view-list").performScrollTo().performClick()
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-newfile").performScrollTo().performClick()
            compose.onNodeWithTag("files-newfile-field").performTextReplacement("created.txt")
            compose.onNodeWithTag("files-newfile-confirm").performClick()
            compose.waitUntil(10_000) { folder.resolve("created.txt").isFile }
            assertEquals(0, folder.resolve("created.txt").length())
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-newfile").performScrollTo().performClick()
            compose.onNodeWithTag("files-newfile-field").performTextReplacement("cancelled.txt")
            compose.onNodeWithTag("files-newfile-cancel").performClick()
            assertTrue(!folder.resolve("cancelled.txt").exists())
            compose.onNodeWithTag("files-entry-note.txt").performScrollTo().performClick()
            waitTag("files-preview-text")
            val bounds = compose.onNodeWithTag("files-preview-dialog").fetchSemanticsNode().boundsInRoot
            assertTrue("Viewer fills the screen", bounds.height > 500f && bounds.width > 500f)
            compose.onNodeWithTag("files-preview-close").performClick()
            compose.onNodeWithTag("files-entry-picture.png").performScrollTo().performClick()
            waitTag("files-preview-image")
            compose.onNodeWithTag("files-preview-close").performClick()
            compose.onNodeWithTag("files-entry-created.txt").performScrollTo().performClick()
            waitTag("files-info-sha")
            compose.onNodeWithTag("files-action-trash").performClick()
            compose.waitUntil(10_000) { !folder.resolve("created.txt").exists() }
            compose.onNodeWithTag("files-home-open").performClick()
            compose.onNodeWithTag("files-home-trash").performScrollTo().performClick()
            val trash =
                container.fileManager.listTrash("app").single {
                    it.originalRelativePath.endsWith("${folder.name}/created.txt")
                }
            compose.onNodeWithTag("files-trash-restore-${trash.entryName}").performScrollTo().performClick()
            compose.waitUntil(10_000) { folder.resolve("created.txt").isFile }
            assertEquals(0, folder.resolve("created.txt").length())
            compose.onNodeWithTag("files-trash-back").performClick()
            compose.onNodeWithTag("files-home-trash").performScrollTo().assertIsDisplayed()
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun closingReaderPreservesDirectoryScrollPosition() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
        val container = app.appContainer
        val folder = app.filesDir.resolve("workspaces/app/work/browser-scroll-${System.nanoTime()}")
        check(folder.mkdirs())
        repeat(80) { folder.resolve("note-${it.toString().padStart(2, '0')}.txt").writeText("Document $it") }
        try {
            compose.setContent {
                MaterialTheme {
                    FilesScreen(
                        container.fileManager,
                        container.safTree,
                        container.featureFiles,
                        initialDirectory = FileScopePath("app", "work/${folder.name}"),
                    )
                }
            }
            waitTag("files-entry-note-00.txt")
            val last = compose.onNodeWithTag("files-entry-note-79.txt")
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(79)
            last.assertIsDisplayed()
            val before = last.fetchSemanticsNode().boundsInRoot.top
            last.performClick()
            waitTag("files-preview-text")
            compose.onNodeWithTag("files-preview-close").performClick()
            last.assertIsDisplayed()
            assertEquals(before, last.fetchSemanticsNode().boundsInRoot.top, 1f)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Suppress("LongMethod") // Real file creation, reader boundaries and decode bounds share one disposable fixture.
    @Test
    fun largeFilesPageThroughCompleteTextAndSampleImages() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
        val container = app.appContainer
        val folder = app.filesDir.resolve("workspaces/app/work/browser-large-${System.nanoTime()}")
        check(folder.mkdirs())
        folder.resolve("full.txt").writeText("a".repeat(90_000) + "END")
        folder.resolve("partial.txt").writeText("a".repeat(1024 * 1024) + "LAST_PAGE")
        val bitmap = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
        val encoded =
            java.io.ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        bitmap.recycle()
        val image = encoded.copyOf(5 * 1024 * 1024)
        folder.resolve("large.png").writeBytes(image)
        folder.resolve("oversize.png").writeBytes(encoded.copyOf(8 * 1024 * 1024 + 1))
        folder.resolve("damaged.png").writeBytes(encoded.copyOfRange(0, 12))
        val decoded = requireNotNull(decodeViewerImage(image))
        assertTrue(decoded.width <= 2048 && decoded.height <= 2048)
        try {
            compose.setContent {
                MaterialTheme {
                    FilesScreen(
                        container.fileManager,
                        container.safTree,
                        container.featureFiles,
                        initialDirectory = FileScopePath("app", "work/${folder.name}"),
                    )
                }
            }
            waitTag("files-entry-full.txt")
            compose.onNodeWithTag("files-entry-full.txt").performClick()
            waitTag("files-preview-text")
            compose.onNodeWithTag("files-text-page").assertDoesNotExist()
            compose.onNodeWithTag("files-viewer-content").performScrollToNode(hasTestTag("files-text-10"))
            compose.onNodeWithTag("files-text-10").assertTextContains("END", substring = true)
            compose.onNodeWithTag("files-preview-close").performClick()
            compose.onNodeWithTag("files-entry-partial.txt").performScrollTo().performClick()
            waitTag("files-text-next")
            compose.onNodeWithTag("files-text-next").performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("files-preview-text").assertTextContains("LAST_PAGE") }.isSuccess
            }
            compose.onNodeWithTag("files-text-previous").performClick()
            waitTag("files-preview-text")
            compose.onNodeWithTag("files-text-page").performClick()
            compose.onNodeWithTag("files-text-page-input").performTextReplacement("2")
            compose.onNodeWithTag("files-text-page-confirm").performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("files-preview-text").assertTextContains("LAST_PAGE") }.isSuccess
            }
            compose.onNodeWithTag("files-preview-close").performClick()
            compose.onNodeWithTag("files-entry-large.png").performScrollTo().performClick()
            waitTag("files-preview-image")
            compose.onNodeWithTag("files-image-too-large").assertDoesNotExist()
            compose.onNodeWithTag("files-preview-close").performClick()
            compose.onNodeWithTag("files-entry-oversize.png").performScrollTo().performClick()
            waitTag("files-image-too-large")
            compose.onNodeWithTag("files-preview-image").assertDoesNotExist()
            compose.onNodeWithTag("files-action-share").assertIsDisplayed()
            compose.onNodeWithTag("files-preview-close").performClick()
            compose.onNodeWithTag("files-entry-damaged.png").performScrollTo().performClick()
            waitTag("files-image-unreadable")
            compose.onNodeWithTag("files-preview-image").assertDoesNotExist()
        } finally {
            folder.deleteRecursively()
        }
    }

    @Suppress("LongMethod") // Verify real reader selection across lazy blocks and restore the clipboard.
    @Test
    fun copiesAcrossVisibleTextBlocksInTheActualReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HelixApplication
        val folder = app.filesDir.resolve("workspaces/app/work/selection-${System.nanoTime()}")
        check(folder.mkdirs())
        folder.resolve("selection.txt").writeText("a\n".repeat(4090) + "\n\nFIRST_END\nSECOND_START")
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        val previous = clipboard.primaryClip
        try {
            val container = app.appContainer
            compose.setContent {
                MaterialTheme {
                    FilesScreen(
                        container.fileManager,
                        container.safTree,
                        container.featureFiles,
                        initialDirectory = FileScopePath("app", "work/${folder.name}"),
                    )
                }
            }
            waitTag("files-entry-selection.txt")
            compose.onNodeWithTag("files-entry-selection.txt").performClick()
            waitTag("files-preview-text")
            val viewport = compose.onNodeWithTag("files-viewer-content")
            viewport.performScrollToNode(hasTestTag("files-text-1"))

            fun point(
                tag: String,
                offset: Int,
                endAnchor: Boolean = false,
            ): androidx.compose.ui.geometry.Offset {
                val node = compose.onNodeWithTag(tag)
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val box = layouts.single().getBoundingBox(offset)
                val local =
                    if (endAnchor) {
                        box.bottomRight
                    } else {
                        box.center
                    }
                return local + node.fetchSemanticsNode().positionInRoot -
                    viewport.fetchSemanticsNode().positionInRoot
            }
            val from = point("files-preview-text", 8184)
            val to = point("files-text-1", "SECOND_START".lastIndex)
            viewport.performTouchInput { longClick(from) }
            compose.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                app.cacheDir.resolve("selection-before-drag.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val anchor = point("files-preview-text", 8190, endAnchor = true)
            val endHandle =
                compose.onNode(
                    androidx.compose.ui.test.SemanticsMatcher("selection end handle") { node ->
                        node.config.any {
                            it.key.name == "SelectionHandleInfo" && it.value.toString().contains("SelectionEnd")
                        }
                    },
                )
            endHandle.performTouchInput {
                swipe(
                    center,
                    center + to - anchor +
                        androidx.compose.ui.geometry
                            .Offset(20f, 0f),
                    durationMillis = 500,
                )
            }
            compose.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                app.cacheDir.resolve("selection-after-drag.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val automation = instrumentation.uiAutomation
            val originalFlags = automation.serviceInfo.flags
            try {
                automation.serviceInfo =
                    automation.serviceInfo.apply {
                        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    }
                var copy: AccessibilityNodeInfo? = null
                compose.waitUntil(5000) {
                    copy =
                        automation.windows
                            .asSequence()
                            .mapNotNull { it.root }
                            .flatMap { root ->
                                val localized = root.findAccessibilityNodeInfosByText("复制")
                                (localized + root.findAccessibilityNodeInfosByText("Copy")).asSequence()
                            }.firstOrNull { it.viewIdResourceName == "android:id/floating_toolbar_menu_item_text" }
                    copy != null
                }
                var target = requireNotNull(copy)
                while (!target.isClickable && target.parent != null) target = target.parent
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
            compose.waitUntil(5000) {
                val copied =
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(app)
                        ?.toString()
                        .orEmpty()
                copied.contains("FIRST_END") && copied.contains("SECOND_START")
            }
        } finally {
            if (previous == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous)
            folder.deleteRecursively()
        }
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
