package com.helix.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual native widget/pixels, only synthetic content in the explicitly isolated test package. */
class FormulaPreviewDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val source = mutableStateOf("\\frac{1}{2}+\\sqrt{9}")
    private val dark = mutableStateOf(false)
    private val scale = mutableStateOf(1f)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture() {
        require(context.packageName == "com.helix.agent.developer.asrvalidation")
        compose.setContent {
            val configuration =
                android.content.res.Configuration(LocalConfiguration.current).apply {
                    fontScale =
                        scale.value
                }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, scale.value),
            ) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Column { MarkdownText("```math\n${source.value}\n```") }
                }
            }
        }
    }

    @Test fun offlineFontsPixelsThemeAndSourceChangeUseTheActualMessageEntry() {
        fixture()
        compose.onNodeWithText("查看公式").performClick()
        val first = readyView()
        assertTrue(eval(first, "!!document.querySelector('.mfrac') && !!document.querySelector('.sqrt')").toBoolean())
        assertEquals("0", eval(first, "Array.from(document.fonts).filter(f=>f.status==='error').length"))
        compose.runOnIdle {
            assertTrue(first.settings.blockNetworkLoads)
            assertFalse(first.settings.allowFileAccess)
            assertFalse(first.settings.allowContentAccess)
            assertFalse(first.settings.domStorageEnabled)
        }
        savePixels(first, "formula-light.png")
        compose.runOnIdle {
            dark.value = true
            scale.value = 1.8f
        }
        compose.waitForIdle()
        val second = readyView()
        assertTrue(first !== second)
        val color = darkColorScheme().surface.toArgb()
        val expected = "rgb(${android.graphics.Color.red(
            color,
        )}, ${android.graphics.Color.green(color)}, ${android.graphics.Color.blue(color)})"
        assertEquals(expected, eval(second, "getComputedStyle(document.body).backgroundColor").trim('"'))
        compose.onNodeWithText("关闭").assertIsDisplayed()
        savePixels(second, "formula-dark-large.png")
        compose.runOnIdle { source.value = "x^2" }
        compose.waitUntil(10_000) { views().isEmpty() }
        compose.onNodeWithText("查看公式").performClick()
        assertTrue(eval(readyView(), "document.querySelector('.katex').textContent.includes('x')").toBoolean())
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(10_000) { views().isEmpty() }
    }

    @Test fun rendererFailureCallbackShowsErrorAndReleasesTheView() {
        fixture()
        compose.onNodeWithText("查看公式").performClick()
        val view = readyView()
        compose.runOnIdle {
            val detail =
                object : android.webkit.RenderProcessGoneDetail() {
                    override fun didCrash() = true

                    override fun rendererPriorityAtExit() = android.webkit.WebView.RENDERER_PRIORITY_BOUND
                }
            assertTrue(view.webViewClient.onRenderProcessGone(view, detail))
        }
        compose.onNodeWithTag("formula-error").assertIsDisplayed()
        compose.waitUntil(10_000) { views().isEmpty() }
        compose.onNodeWithText("关闭").performClick()
    }

    @Test fun invalidOversizedAndRapidCancelledPreviewsKeepTheirSource() {
        source.value = "\\unknownCommand{"
        fixture()
        compose.onNodeWithText("查看公式").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("formula-error"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("formula-error").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { source.value = "x".repeat(8193) }
        compose.onNodeWithText("查看公式").performClick()
        compose.onNodeWithTag("formula-error").assertIsDisplayed()
        assertTrue(views().isEmpty())
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { source.value = "x=1" }
        repeat(3) {
            compose.onNodeWithText("查看公式").performClick()
            compose.onNodeWithText("关闭").performClick()
        }
        compose.onNodeWithText("查看公式").performClick()
        readyView()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(10_000) { views().isEmpty() }
        assertEquals("x=1", source.value)
    }

    @Test fun formulaDataCannotCreateHtmlNavigateOrExpandMacrosWithoutBound() {
        source.value = "\\text{</script><img src=x>}"
        fixture()
        compose.onNodeWithText("查看公式").performClick()
        val view = readyView()
        assertEquals("0", eval(view, "document.images.length"))
        assertEquals("0", eval(view, "document.querySelectorAll('iframe,a').length"))
        eval(view, "window.location.href='https://example.invalid/not-loaded'; true")
        Thread.sleep(150)
        assertEquals("\"${FormulaPreviewPolicy.PAGE}\"", eval(view, "window.location.href"))
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { source.value = "\\gdef\\loop{\\loop}\\loop" }
        compose.onNodeWithText("查看公式").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("formula-error"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("formula-error").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(10_000) { views().isEmpty() }
    }

    private fun views(): List<FormulaPreviewWebView> {
        val found = mutableListOf<FormulaPreviewWebView>()

        fun visit(view: View) {
            if (view is FormulaPreviewWebView) found += view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return found
    }

    private fun readyView(): FormulaPreviewWebView {
        var result: FormulaPreviewWebView? = null
        compose.waitUntil(10_000) {
            val view = views().singleOrNull()
            if (view != null && eval(view, "document.body.dataset.state") == "\"ready\"" &&
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("formula-loading"),
                    ).fetchSemanticsNodes()
                    .isEmpty()
            ) {
                result = view
            }

            result != null
        }
        return requireNotNull(result)
    }

    private fun eval(
        view: FormulaPreviewWebView,
        expression: String,
    ): String {
        val value = AtomicReference<String>()
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(expression) {
                value.set(it)
                done.countDown()
            }
        }
        check(done.await(10, TimeUnit.SECONDS))
        return value.get()
    }

    private fun savePixels(
        view: FormulaPreviewWebView,
        name: String,
    ) {
        val done = CountDownLatch(1)
        val position = IntArray(2)
        var width = 0
        var height = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.postVisualStateCallback(
                1,
                object : android.webkit.WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        done.countDown()
                    }
                },
            )
            view.getLocationOnScreen(position)
            width = view.width
            height = view.height
        }
        check(done.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            assertTrue(width > 150 && height > 100)
            val crop = Bitmap.createBitmap(screen, position[0], position[1], width, height)
            try {
                assertNonblank(crop)
                File(context.cacheDir, name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                crop.recycle()
            }
        } finally {
            screen.recycle()
        }
    }

    private fun assertNonblank(bitmap: Bitmap) {
        val colors = hashSetOf<Int>()
        for (y in 0 until bitmap.height step 3) {
            for (x in 0 until bitmap.width step 3) colors += bitmap.getPixel(x, y)
        }
        assertTrue("DOM alone must not pass a blank preview", colors.size > 8)
    }
}
