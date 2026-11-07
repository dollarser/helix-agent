package com.helix.ui.shared

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The real Markdown route, synthetic content only, no account or provider interaction. */
class InlineDiagramDeviceTest {
    private val clipboardFixture = PreviewClipboardFixture()

    @org.junit.Before
    fun preserveClipboard() = clipboardFixture.capture()

    @org.junit.After
    fun restoreClipboard() = clipboardFixture.restore()

    @get:Rule val compose = createComposeRule()
    private var body by mutableStateOf("```mermaid\nflowchart TD\n A[合成输入] --> B[安全结果]\n```")
    private var dark by mutableStateOf(false)
    private var narrow by mutableStateOf(false)
    private var scale by mutableStateOf(1f)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(owner: androidx.lifecycle.LifecycleOwner? = null) {
        requireSharedTestApplication(context)
        InlineDiagramCache.clear()
        compose.setContent {
            val config =
                android.content.res
                    .Configuration(LocalConfiguration.current)
                    .apply { fontScale = scale }
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale),
                androidx.lifecycle.compose.LocalLifecycleOwner provides
                    (owner ?: androidx.lifecycle.compose.LocalLifecycleOwner.current),
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Column(Modifier.fillMaxWidth().systemBarsPadding()) {
                        Column(if (narrow) Modifier.width(180.dp) else Modifier.fillMaxWidth()) { MarkdownText(body) }
                    }
                }
            }
        }
    }

    @Test fun defaultInlinePixelsCopyToggleCacheThemeAndSourceChange() {
        fixture()
        ready()
        val first = cached("flowchart TD\n A[合成输入] --> B[安全结果]", BlockPreviewKind.MERMAID)
        assertPixels(first)
        saveImage("mermaid-inline-light.png")
        compose.onNodeWithTag("inline-diagram-copy").performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        compose.runOnIdle {
            assertEquals(
                "flowchart TD\n A[合成输入] --> B[安全结果]",
                clipboard.primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString(),
            )
        }
        repeat(3) {
            compose.onNodeWithTag("inline-diagram-toggle").performClick()
            compose.onNode(hasText("预览") or hasText("Preview")).assertIsDisplayed()
            assertEquals(0, engines())
            compose.onNodeWithTag("inline-diagram-toggle").performClick()
            ready()
            assertTrue(first === cached("flowchart TD\n A[合成输入] --> B[安全结果]", BlockPreviewKind.MERMAID))
        }
        compose.runOnIdle {
            dark = true
            scale = 1.8f
        }
        ready()
        assertPixels(cached("flowchart TD\n A[合成输入] --> B[安全结果]", BlockPreviewKind.MERMAID))
        saveImage("mermaid-inline-dark-large.png")
        compose.runOnIdle { body = "```mermaid\nflowchart TD\n A[新内容] --> B[后续结果]\n```" }
        ready()
        val changed = cached("flowchart TD\n A[新内容] --> B[后续结果]", BlockPreviewKind.MERMAID)
        assertTrue(changed !== first)
        assertPixels(changed)
    }

    @Test fun unfinishedFenceDoesNotStartRendererThenSvgRendersByDefault() {
        body = "```mermaid\nflowchart TD\n A-->B"
        fixture()
        compose.onNodeWithText("flowchart TD", substring = true).assertIsDisplayed()
        assertEquals(0, engines())
        assertEquals(1, InlineDiagramCache.renderer.availablePermits)
        val svg =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\">" +
                "<rect x=\"5\" y=\"5\" width=\"190\" height=\"90\" fill=\"#2277dd\"/>" +
                "<text x=\"30\" y=\"58\" fill=\"white\" font-size=\"24\">合成 SVG</text></svg>"
        compose.runOnIdle { body = "```svg\n$svg\n```" }
        ready()
        val image = cached(svg, BlockPreviewKind.SVG)
        assertPixels(image)
        assertTrue("No unused right-hand viewport in the cached SVG", image.width.toFloat() / image.height < 2.1f)
        val bounds = compose.onNodeWithTag("inline-diagram-image").fetchSemanticsNode().boundsInRoot
        assertTrue("Image layout preserves its cached content aspect", bounds.width / bounds.height < 2.1f)
        assertTrue(
            "Rasterize vectors at the displayed resolution",
            image.width >= bounds.width * .98f ||
                image.height >= bounds.height * .98f,
        )
        saveImage("svg-inline.png")
        compose.runOnIdle { body = "```svg\n<!DOCTYPE svg SYSTEM 'https://example.invalid/x'>$svg\n```" }
        compose.waitUntil(
            10_000,
        ) { compose.onAllNodes(hasTestTag("inline-diagram-error")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("inline-diagram-copy").assertIsDisplayed()
        assertEquals(0, engines())
        assertEquals(1, InlineDiagramCache.renderer.availablePermits)
        val oversized = "x".repeat(65_537) + "SYNTHETIC-TAIL"
        compose.runOnIdle { body = "```svg\n$oversized\n```" }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("inline-diagram-error")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("inline-diagram-copy").performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        compose.runOnIdle {
            assertEquals(
                oversized,
                clipboard.primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString(),
            )
        }
        assertEquals(0, engines())
    }

    @Test fun narrowLargeTypeAndLifecyclePauseReleaseThenRestoreCachedImage() {
        narrow = true
        scale = 1.8f
        lateinit var owner: PreviewOwner
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = PreviewOwner()
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        fixture(owner)
        ready()
        compose.onNodeWithTag("inline-diagram-copy").assertIsDisplayed()
        compose.onNodeWithTag("inline-diagram-toggle").assertIsDisplayed()
        saveImage("mermaid-inline-narrow-large.png", "inline-diagram")
        compose.onNodeWithTag("inline-diagram-image").performClick()
        compose.onNodeWithTag("block-preview-dialog").assertIsDisplayed()
        compose.onNode(hasText("关闭") or hasText("Close")).performClick()
        ready()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.waitUntil(10_000) {
            engines() == 0 && InlineDiagramCache.renderer.availablePermits == 1 &&
                compose.onAllNodes(hasTestTag("inline-diagram-image")).fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        ready()
    }

    @Test fun longLazyConversationCancelsOffscreenWorkAndBoundsEngines() {
        requireSharedTestApplication(context)
        InlineDiagramCache.clear()
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        var shown by mutableStateOf(true)
        compose.setContent {
            list =
                androidx.compose.foundation.lazy
                    .rememberLazyListState()
            MaterialTheme {
                if (shown) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = list,
                        modifier = Modifier.systemBarsPadding().testTag("diagram-list"),
                    ) {
                        items(24) { index ->
                            MarkdownText("Synthetic row $index: \\(x_$index+1\\).")
                            if (index % 2 == 0) {
                                MarkdownText("```mermaid\nflowchart TD\n A[合成 $index] --> B[结果 $index]\n```")
                            } else {
                                MarkdownText(
                                    "```svg\n<svg viewBox=\"0 0 160 90\"><rect width=\"160\" height=\"90\" " +
                                        "fill=\"#2277dd\"/><text x=\"10\" y=\"50\" " +
                                        "fill=\"white\">合成 $index</text></svg>\n```",
                                )
                            }
                        }
                    }
                }
            }
        }
        for (index in listOf(0, 8, 18, 23, 0)) {
            compose.onNodeWithTag("diagram-list").performScrollToIndex(index)
            compose.waitUntil(15_000) {
                assertTrue("At most one inline engine", engines() <= 1)
                compose.onAllNodes(hasTestTag("inline-diagram-image")).fetchSemanticsNodes().isNotEmpty() &&
                    engines() == 0 && InlineDiagramCache.renderer.availablePermits == 1
            }
        }
        repeat(8) { compose.onNodeWithTag("diagram-list").performScrollToIndex(if (it % 2 == 0) 0 else 23) }
        compose.runOnIdle { shown = false }
        compose.waitUntil(10_000) { engines() == 0 && InlineDiagramCache.renderer.availablePermits == 1 }
        assertTrue(InlineDiagramCache.retainedBytes <= 16 * 1024 * 1024)
    }

    private class PreviewOwner : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    private fun ready() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("inline-diagram-image")).fetchSemanticsNodes().size == 1 &&
                engines() == 0 && InlineDiagramCache.renderer.availablePermits == 1
        }
    }

    private fun cached(
        source: String,
        kind: BlockPreviewKind,
    ): Bitmap {
        val width =
            compose
                .onNodeWithTag("inline-diagram")
                .fetchSemanticsNode()
                .size.width
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        return requireNotNull(
            InlineDiagramCache.get(
                InlineDiagramInput(
                    source,
                    kind,
                    colors.surface.toArgb(),
                    colors.onSurface.toArgb(),
                    scale,
                    width,
                    context.resources.displayMetrics.density,
                ).key,
            ),
        )
    }

    private fun assertPixels(bitmap: Bitmap) {
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        assertTrue(bitmap.width.toLong() * bitmap.height <= 2_000_000)
        val colors = HashSet<Int>()
        for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) colors += bitmap.getPixel(x, y)
        assertTrue("Actual renderer snapshot must contain glyph/shape pixels", colors.size > 8)
    }

    private fun engines(): Int {
        var count = 0

        fun visit(view: View) {
            if (view is OfflinePreviewWebView) count++
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return count
    }

    private fun saveImage(
        name: String,
        tag: String = "inline-diagram-image",
    ) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val crop =
            Bitmap.createBitmap(
                image,
                bounds.left.toInt().coerceAtLeast(0),
                bounds.top.toInt().coerceAtLeast(0),
                bounds.width.toInt().coerceAtMost(image.width - bounds.left.toInt()),
                bounds.height.toInt().coerceAtMost(image.height - bounds.top.toInt()),
            )
        File(context.cacheDir, name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        image.recycle()
    }
}
