package com.helix.app.eval

import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.helix.app.ui.BackgroundDisplayPreview
import com.helix.app.ui.LocalizedComposeActivity
import com.helix.feature.conversation.presentation.DisplayExecutionState
import com.helix.feature.conversation.presentation.DisplayExecutionUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Suppress("TooManyFunctions")
class BackgroundPreviewUiDeviceTest {
    private var display by mutableStateOf(
        DisplayExecutionUi("preview", "turn", DisplayExecutionState.ACTIVE, generation = 1),
    )
    private lateinit var inputModeManager: InputModeManager
    private val reads = AtomicInteger()
    private val requestedWidths = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private val missing = AtomicBoolean()
    private val landscape = AtomicBoolean()
    private val underlyingClicks = AtomicInteger()
    private val underlyingScroll = ScrollState(0)
    private var previewHeightFraction by mutableStateOf(1f)
    private var fontScale by mutableStateOf(1f)
    private var recommendedTimeout by mutableStateOf<Long?>(null)
    private val image =
        Bitmap.createBitmap(240, 426, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }

    private val landscapeImage =
        Bitmap.createBitmap(420, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                MaterialTheme {
                    inputModeManager = LocalInputModeManager.current
                    val accessibility =
                        recommendedTimeout?.let { timeout ->
                            object : AccessibilityManager {
                                override fun calculateRecommendedTimeoutMillis(
                                    originalTimeoutMillis: Long,
                                    containsIcons: Boolean,
                                    containsText: Boolean,
                                    containsControls: Boolean,
                                ) = timeout
                            }
                        } ?: LocalAccessibilityManager.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale),
                        LocalAccessibilityManager provides accessibility,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().verticalScroll(underlyingScroll)) {
                                repeat(40) {
                                    Text(
                                        "Page $it",
                                        Modifier.fillMaxWidth().height(48.dp).testTag("page-$it").clickable {
                                            underlyingClicks.incrementAndGet()
                                        },
                                    )
                                }
                            }
                            Box(Modifier.fillMaxWidth().fillMaxHeight(previewHeightFraction).testTag("preview-host")) {
                                BackgroundDisplayPreview(display, readName = {
                                    if (it.lastObservedPackage == "second") "设置" else "酷安"
                                }) { _, width ->
                                    requestedWidths.add(width)
                                    reads.incrementAndGet()
                                    if (missing.get()) {
                                        null
                                    } else if (landscape.get()) {
                                        landscapeImage
                                    } else {
                                        image
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    @After fun cleanup() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun singleTapShowsOverlayWithoutResizingAndDoubleTapOnlyZooms() {
        awaitFrame()
        val small = viewport().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("background-preview-hide").assertDoesNotExist()
        compose.onNodeWithTag("background-preview-title", useUnmergedTree = true).assertDoesNotExist()
        save("default-hidden-controls")
        viewport().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(small, viewport().fetchSemanticsNode().boundsInRoot)
        viewport().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
        repeat(3) {
            viewport().performTouchInput { doubleClick() }
            compose.mainClock.advanceTimeBy(400)
            val expanded = viewport().fetchSemanticsNode()
            assertTrue(expanded.boundsInRoot.width > small.width)
            val maximumDp = expanded.config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive
            assertEquals(maximumDp * compose.activity.resources.displayMetrics.density, expanded.boundsInRoot.width, 1f)
            // Double-tap must reach the same full available range as continuous resizing.
            viewport().performSemanticsAction(SemanticsActions.SetProgress) { it(10000f) }
            assertEquals(expanded.boundsInRoot, viewport().fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
            if (it == 0) save("double-tap-expanded")
            viewport().performTouchInput { doubleClick() }
            compose.mainClock.advanceTimeBy(400)
            assertEquals(small.width, viewport().fetchSemanticsNode().boundsInRoot.width, 1f)
        }
        assertEquals(0, underlyingClicks.get())
        assertEquals(0, underlyingScroll.value)
    }

    @Test fun doubleTapWithControlsVisiblePreservesChromeAndNewGenerationStartsClean() {
        awaitFrame()
        showControls()
        val compact = viewport().fetchSemanticsNode().boundsInRoot.width
        viewport().performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(400)
        assertTrue(viewport().fetchSemanticsNode().boundsInRoot.width > compact)
        compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
        viewport().performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(compact, viewport().fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
        compose.onNodeWithTag("background-preview-hide").performClick()
        compose.onNodeWithTag("background-preview-reopen").assertIsDisplayed()
        compose.runOnIdle { display = display.copy(generation = display.generation + 1) }
        viewport().assertIsDisplayed()
        compose.onNodeWithTag("background-preview-reopen").assertDoesNotExist()
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, underlyingClicks.get())
        assertEquals(0, underlyingScroll.value)
    }

    @Test fun controlsAutoHideButPointerHoldKeepsCloseReachable() {
        awaitFrame()
        showControls()
        compose.mainClock.autoAdvance = false
        viewport().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
        viewport().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        showControls()
        compose.onNodeWithTag("background-preview-hide").performClick()
        compose.onNodeWithTag("background-preview-reopen").assertIsDisplayed()
    }

    @Test fun cancelledPointerRestartsDismissalAndRepeatedControlsRemainUsable() {
        awaitFrame()
        repeat(2) {
            showControls()
            compose.mainClock.autoAdvance = false
            viewport().performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
            viewport().performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
            compose.mainClock.autoAdvance = true
        }
        showControls()
        compose.onNodeWithTag("background-preview-hide").performClick()
        compose.onNodeWithTag("background-preview-reopen").performClick()
        viewport().assertIsDisplayed()
        assertEquals(0, underlyingClicks.get())
        assertEquals(0, underlyingScroll.value)
    }

    @Test fun focusedControlsRemainReachableUntilFocusLeavesPreview() {
        awaitFrame()
        showControls()
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        try {
            viewport().performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
            compose.onNodeWithTag("page-0").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0, underlyingClicks.get())
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Touch) }
        }
    }

    @Test fun constrainedHeightReboundsExpandedWindowAndRestoresItsRequestedSize() {
        awaitFrame()
        viewport().performSemanticsAction(SemanticsActions.SetProgress) { it(10_000f) }
        val expanded = viewport().fetchSemanticsNode().boundsInRoot
        repeat(2) {
            compose.runOnIdle { previewHeightFraction = 0.35f }
            val reduced = viewport().fetchSemanticsNode().boundsInRoot
            val host = compose.onNodeWithTag("preview-host").fetchSemanticsNode().boundsInRoot
            assertTrue(reduced.width < expanded.width)
            assertTrue(reduced.left >= host.left && reduced.top >= host.top)
            assertTrue(reduced.right <= host.right && reduced.bottom <= host.bottom)
            assertEquals(240f / 426f, reduced.width / reduced.height, 0.01f)
            if (it == 0) save("constrained-height")
            showControls()
            compose.onNodeWithTag("background-preview-hide").performClick()
            compose.onNodeWithTag("background-preview-reopen").performClick()
            assertEquals(reduced.width, viewport().fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.runOnIdle { previewHeightFraction = 1f }
            assertEquals(expanded.width, viewport().fetchSemanticsNode().boundsInRoot.width, 1f)
        }
        assertEquals(0, underlyingClicks.get())
        assertEquals(0, underlyingScroll.value)
    }

    @Test fun draggingThePreviewMovesOnlyTheLocalWindowWithoutScrollingBehindIt() {
        awaitFrame()
        val before = viewport().fetchSemanticsNode().boundsInRoot
        viewport().performTouchInput { swipe(center, center + Offset(-45f, -65f), durationMillis = 400) }
        val after = viewport().fetchSemanticsNode().boundsInRoot
        assertTrue(after.left < before.left && after.top < before.top)
        assertEquals(before.width, after.width, 1f)
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, underlyingClicks.get())
        assertEquals(0, underlyingScroll.value)
    }

    @Test fun landscapeCloseKeepsItsFullTargetWithoutOverlappingResize() {
        awaitFrame()
        landscape.set(true)
        compose.waitUntil(10_000) {
            val bounds = viewport().fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(bounds.width / bounds.height - 2.1f) < 0.01f
        }
        for (width in listOf(96f, 112f, 260f)) {
            viewport().performSemanticsAction(SemanticsActions.SetProgress) { it(width) }
            val before = viewport().fetchSemanticsNode().boundsInRoot
            showControls()
            assertEquals(before, viewport().fetchSemanticsNode().boundsInRoot)
            val close = compose.onNodeWithTag("background-preview-hide").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertTrue(close.width >= 48f * density - 1f && close.height >= 48f * density - 1f)
            assertTrue(close.left >= before.left && close.top >= before.top)
            assertTrue(close.right <= before.right && close.bottom <= before.bottom)
            val handle = compose.onNodeWithTag("background-preview-resize", useUnmergedTree = true)
            if (before.height < 72f * density) {
                handle.assertDoesNotExist()
            } else {
                assertTrue(handle.fetchSemanticsNode().boundsInRoot.top >= close.bottom)
            }
            if (width == 96f) save("landscape-close-target")
            // Exercise the lower-right part formerly intercepted by the resize handle.
            compose.onNodeWithTag("background-preview-hide").performTouchInput {
                click(bottomRight - Offset(12.dp.toPx(), 4.dp.toPx()))
            }
            compose.onNodeWithTag("background-preview-reopen").performClick()
            assertEquals(before.width, viewport().fetchSemanticsNode().boundsInRoot.width, 1f)
        }
        assertEquals(0, underlyingClicks.get())
        assertEquals(0, underlyingScroll.value)
    }

    @Test fun automaticDismissalHonorsAccessibilityTimeout() {
        awaitFrame()
        compose.runOnIdle { recommendedTimeout = 10_000L }
        showControls()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun accessibleZoomAndHideRemainAvailableWithoutVisibleChrome() {
        awaitFrame()
        val before = viewport().fetchSemanticsNode().boundsInRoot.width
        val zoomActions = viewport().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(zoomActions.single { it.label == "展开后台屏" }.action()) }
        assertTrue(viewport().fetchSemanticsNode().boundsInRoot.width > before)
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
        val hideActions = viewport().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(hideActions.single { it.label == "隐藏" }.action()) }
        compose.onNodeWithTag("background-preview-reopen").performClick()
        image().assertIsDisplayed()
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun qualityFollowsWindowSizeWithoutRemovingTheCurrentFrame() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        for (width in listOf(175f, 260f, 112f)) {
            requestedWidths.clear()
            compose.onNodeWithTag("background-preview-viewport").performSemanticsAction(
                SemanticsActions.SetProgress,
            ) { it(width) }
            image().assertIsDisplayed()
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
        showControls()
        val before = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        val corner =
            compose
                .onNodeWithTag(
                    "background-preview-resize",
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        assertEquals(24f * compose.activity.resources.displayMetrics.density, corner.width, 1f)
        compose.onNodeWithTag("background-preview-resize", useUnmergedTree = true).performTouchInput {
            swipe(center, center + Offset(30f, 50f), durationMillis = 400)
        }
        val after = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width)
    }

    @Test fun adjustableViewportIsContinuousAndBoundedWithoutObscuringTheImage() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        compose.onNodeWithTag("background-preview-viewport").performSemanticsAction(
            SemanticsActions.SetProgress,
        ) { it(175f) }
        compose.onNodeWithTag("background-preview-resize", useUnmergedTree = true).assertDoesNotExist()
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
            SemanticsActions.SetProgress,
        ) { it(10000f) }
        val window = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(window.left >= root.left && window.top >= root.top)
        assertTrue(window.right <= root.right && window.bottom <= root.bottom)
    }

    @Test fun pinchScalesThePreviewAndHideReopenRetainsItsSize() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        compose.onNodeWithTag("background-preview-resize", useUnmergedTree = true).assertDoesNotExist()
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
        showControls()
        compose.onNodeWithTag("background-preview-hide").performClick()
        compose.onNodeWithTag("background-preview-reopen").performClick()
        val reopened = compose.onNodeWithTag("background-preview-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(after.width, reopened.width, 1f)
    }

    @Test fun unavailableFramesKeepGeometryAndClearPixelsThenRecover() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        image().assertIsDisplayed()
        val original = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
        missing.set(true)
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("background-preview-image"),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isEmpty()
        }
        val empty = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
        save("unavailable-controls-hidden")
        assertEquals(original.width, empty.width, 1f)
        assertEquals(original.height, empty.height, 1f)
        assertEquals(original.top, empty.top, 1f)
        assertEquals(original.left, empty.left, 1f)
        image().assertDoesNotExist()
        viewport().performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(400)
        assertTrue(viewport().fetchSemanticsNode().boundsInRoot.width > original.width)
        viewport().performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(original.width, viewport().fetchSemanticsNode().boundsInRoot.width, 1f)
        showControls()
        compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
        save("unavailable-controls-visible")
        missing.set(false)
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("background-preview-image"),
                    useUnmergedTree = true,
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

    @Test fun transientControlsOverlayRatherThanShrinkTheFrameAtEveryFontAndWidth() {
        compose.waitUntil(10_000) { reads.get() > 0 }
        showControls()
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            for (width in listOf(96f, 112f, 260f)) {
                viewport().performSemanticsAction(SemanticsActions.SetProgress) { it(width) }
                val title =
                    compose
                        .onNodeWithTag("background-preview-title", useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot
                val close = compose.onNodeWithTag("background-preview-hide").fetchSemanticsNode().boundsInRoot
                val bar =
                    compose
                        .onNodeWithTag("background-preview-controls", useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .boundsInRoot
                assertEquals(title.center.y, close.center.y, 1f)
                assertTrue(title.top >= bar.top && title.bottom <= bar.bottom)
                val density = compose.activity.resources.displayMetrics.density
                assertTrue(close.width >= 48f * density - 1f && close.height >= 48f * density - 1f)
                val bounds = viewport().fetchSemanticsNode().boundsInRoot
                val window = compose.onNodeWithTag("background-preview").fetchSemanticsNode().boundsInRoot
                assertEquals(window, bounds)
                assertEquals(bounds.top, bar.top, 1f)
                assertEquals(240f / 426f, bounds.width / bounds.height, 0.01f)
            }
            save("header-$scale")
        }
    }

    private fun awaitFrame() = compose.waitUntil(10_000) { reads.get() > 0 }

    private fun viewport() = compose.onNodeWithTag("background-preview-viewport")

    private fun image() = compose.onNodeWithTag("background-preview-image", useUnmergedTree = true)

    private fun showControls() {
        viewport().performTouchInput { click() }
        // Let combinedClickable resolve the single tap after its double-tap window.
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("background-preview-hide").assertIsDisplayed()
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
        val node = image()
        node.assertIsDisplayed()
        val small = node.fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("background-preview-controls", useUnmergedTree = true).assertDoesNotExist()
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
                "background-preview-viewport",
            ).performTouchInput { swipe(center, center + Offset(-50f, -80f)) }
        compose.activityRule.scenario.recreate()
        image().assertIsDisplayed()
        assertTrue(node.fetchSemanticsNode().boundsInRoot.width > small)
        showControls()
        compose.onNodeWithTag("background-preview-hide").performClick()
        image().assertDoesNotExist()
        val pausedReads = reads.get()
        Thread.sleep(600)
        assertTrue(reads.get() <= pausedReads + 1)
        compose.onNodeWithTag("background-preview-reopen").performClick()
        image().assertIsDisplayed()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.CONNECTION_LOST) }
        compose.onNodeWithTag("background-preview").assertDoesNotExist()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.ACTIVE, generation = 2) }
        image().assertIsDisplayed()
        compose.runOnIdle { display = display.copy(state = DisplayExecutionState.RELEASED) }
        compose.onNodeWithTag("background-preview").assertDoesNotExist()
    }
}
