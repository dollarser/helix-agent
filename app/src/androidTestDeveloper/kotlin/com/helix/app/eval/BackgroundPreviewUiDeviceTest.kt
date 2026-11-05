package com.helix.app.eval

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.helix.app.chat.DisplayExecutionState
import com.helix.app.chat.DisplayExecutionUi
import com.helix.app.ui.BackgroundDisplayPreview
import com.helix.app.ui.LocalizedComposeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BackgroundPreviewUiDeviceTest {
    private var display by mutableStateOf(
        DisplayExecutionUi("preview", "turn", DisplayExecutionState.ACTIVE, generation = 1),
    )
    private val reads = AtomicInteger()
    private val requestedWidths = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val missing = AtomicBoolean()
    private var fontScale by mutableStateOf(1f)
    private val image =
        Bitmap.createBitmap(240, 426, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                MaterialTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        Box(Modifier.fillMaxSize()) {
                            BackgroundDisplayPreview(display, readName = {
                                if (it.lastObservedPackage == "second") "设置" else "酷安"
                            }) { _, width ->
                                requestedWidths.add(width)
                                reads.incrementAndGet()
                                if (missing.get()) null else image
                            }
                        }
                    }
                }
            }
        }

    @After fun cleanup() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun qualityFollowsWindowSizeWithoutRemovingTheCurrentFrame() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        for (width in listOf(175f, 260f, 112f)) {
            requestedWidths.clear()
            compose.onNodeWithTag("background-preview-viewport").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetProgress,
            ) { it(width) }
            compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
            val pixels =
                compose
                    .onNodeWithTag("background-preview-viewport")
                    .fetchSemanticsNode()
                    .boundsInRoot.width
            val expected =
                com.helix.app.ui
                    .previewFrameWidth(pixels, 480)
            compose.waitUntil(10_000) { requestedWidths.contains(expected) }
        }
    }

    @Test fun compactCornerRetainsADraggableAreaWithoutAnOpaquePanel() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        val before = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        val corner = compose.onNodeWithTag("background-preview-resize").fetchSemanticsNode().boundsInRoot
        assertEquals(24f * compose.activity.resources.displayMetrics.density, corner.width, 1f)
        compose.onNodeWithTag("background-preview-resize").performTouchInput {
            swipe(center, center + Offset(30f, 50f), durationMillis = 400)
        }
        val after = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width)
    }

    @Test fun adjustableViewportIsContinuousAndBoundedWithoutObscuringTheImage() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        compose.onNodeWithTag("background-preview-viewport").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) { it(175f) }
        compose.onNodeWithTag("background-preview-resize").assertIsDisplayed()
        val before = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("background-preview-viewport").performTouchInput {
            pinch(
                center - Offset(12f, 24f),
                center + Offset(12f, 24f),
                center - Offset(24f, 48f),
                center + Offset(24f, 48f),
                durationMillis = 400,
            )
        }
        val after = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width)
        assertEquals(240f / 426f, after.width / after.height, 0.01f)
        compose.onNodeWithTag("background-preview-viewport").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress,
        ) { it(10000f) }
        val window = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(window.left >= root.left && window.top >= root.top)
        assertTrue(window.right <= root.right && window.bottom <= root.bottom)
    }

    @Test fun pinchScalesThePreviewAndHideReopenRetainsItsSize() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        compose.onNodeWithTag("background-preview-resize").assertIsDisplayed()
        val before = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("background-preview-viewport").performTouchInput {
            pinch(
                center - Offset(12f, 24f),
                center + Offset(12f, 24f),
                center - Offset(35f, 70f),
                center + Offset(35f, 70f),
                durationMillis = 500,
            )
        }
        val after = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width)
        compose.onNodeWithTag("background-preview-hide").performClick()
        compose.onNodeWithTag("background-preview-reopen").performClick()
        val reopened = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(after.width, reopened.width, 1f)
    }

    @Test fun unavailableFramesKeepGeometryAndClearPixelsThenRecover() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
        val original = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
        missing.set(true)
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("background-preview-image"),
                ).fetchSemanticsNodes()
                .isEmpty()
        }
        val empty = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
        save("unavailable")
        assertEquals(original.width, empty.width, 1f)
        assertEquals(original.height, empty.height, 1f)
        assertEquals(original.top, empty.top, 1f)
        assertEquals(original.left, empty.left, 1f)
        compose.onNodeWithTag("background-preview-image").assertDoesNotExist()
        save("unavailable")
        missing.set(false)
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("background-preview-image"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.runOnIdle { display = display.copy(lastObservedPackage = "second") }
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("设置"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        val recovered = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(original.height, recovered.height, 1f)
        save("recovered")
    }

    @Test fun compactHeaderCentersTitleAndCloseAtNormalAndLargeFont() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            val title = compose.onNodeWithText("酷安").fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithTag("background-preview-hide").fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("background-preview-drag").fetchSemanticsNode().boundsInRoot
            save("header-$scale")
            assertEquals(title.center.y, close.center.y, 1f)
            assertTrue(title.top >= bar.top && title.bottom <= bar.bottom)
            if (scale == 1f) {
                val density = compose.activity.resources.displayMetrics.density
                assertTrue("small-window bar: $bar", bar.height <= 20f * density + 1f)
            }
            val image = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals("No spacer below title bar", bar.bottom, image.top, 1f)
            save("header-$scale")
        }
    }

    @Test fun headerTracksWindowWidthWithoutClippingTitleOrClose() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        for (width in listOf(112f, 160f, 260f)) {
            compose.onNodeWithTag("background-preview-viewport").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetProgress,
            ) { it(width) }
            val bar = compose.onNodeWithTag("background-preview-drag").fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("background-preview-title").fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithTag("background-preview-hide").fetchSemanticsNode().boundsInRoot
            val window = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            val expected =
                com.helix.app.ui
                    .previewHeaderHeight(window.width / density) * density
            assertEquals(expected, bar.height, 1f)
            assertEquals(title.center.y, close.center.y, 1f)
            assertTrue(title.top >= bar.top && title.bottom <= bar.bottom)
            assertTrue(close.top >= bar.top && close.bottom <= bar.bottom)
        }
    }

    private fun save(name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = java.io.File(context.filesDir, "validation/background-preview/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun livePreviewExpandsDragsHidesRestoresAndClearsOnRelease() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        val node = compose.onNodeWithTag("background-preview-image")
        node.assertIsDisplayed()
        val small = node.fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithText("酷安").assertIsDisplayed()
        val top =
            compose
                .onNodeWithTag("background-preview")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(node.fetchSemanticsNode().boundsInRoot.top - top < small * 0.4f)
        node.performTouchInput {
            pinch(
                center - Offset(12f, 24f),
                center + Offset(12f, 24f),
                center - Offset(35f, 70f),
                center + Offset(35f, 70f),
                durationMillis = 500,
            )
        }
        assertTrue(node.fetchSemanticsNode().boundsInRoot.width > small)
        compose
            .onNodeWithTag(
                "background-preview-drag",
            ).performTouchInput { swipe(center, center + Offset(-50f, -80f)) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
        assertTrue(node.fetchSemanticsNode().boundsInRoot.width > small)
        compose.onNodeWithTag("background-preview-hide").performClick()
        compose.onNodeWithTag("background-preview-image").assertDoesNotExist()
        val pausedReads = reads.get()
        Thread.sleep(600)
        assertTrue(reads.get() <= pausedReads + 1)
        compose.onNodeWithTag("background-preview-reopen").performClick()
        compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.CONNECTION_LOST) }
        compose.onNodeWithTag("background-preview").assertDoesNotExist()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.ACTIVE, generation = 2) }
        compose.onNodeWithTag("background-preview-image").assertIsDisplayed()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.RELEASED) }
        compose.onNodeWithTag("background-preview").assertDoesNotExist()
    }
}
