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
class BlockPreviewDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val language = mutableStateOf("math")
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
                    Column { MarkdownText("```${language.value}\n${source.value}\n```") }
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
        savePixels(first, "block-preview-light.png")
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
        savePixels(second, "block-preview-dark-large.png")
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
        compose.onNodeWithTag("block-preview-error").assertIsDisplayed()
        compose.waitUntil(10_000) { views().isEmpty() }
        compose.onNodeWithText("关闭").performClick()
    }

    @Test fun offlineMermaidFlowAndSequenceRenderInLightAndDarkLargeType() {
        language.value = "mermaid"
        source.value = "flowchart TD\n A[合成输入] --> B[安全结果]"
        fixture()
        compose.onNodeWithText("查看图表").performClick()
        val first = readyView()
        assertTrue(eval(first, "!!document.querySelector('#preview svg')").toBoolean())
        assertTrue(eval(first, "document.getElementById('preview').textContent.includes('安全结果')").toBoolean())
        assertEquals(
            "0",
            eval(
                first,
                "document.querySelectorAll('foreignObject,iframe,img,image,a,script:not([src]):not([nonce])').length",
            ),
        )
        savePixels(first, "mermaid-light.png")
        compose.runOnIdle {
            dark.value = true
            scale.value = 1.8f
        }
        compose.waitForIdle()
        val second = readyView()
        assertTrue(first !== second)
        savePixels(second, "mermaid-dark-large.png")
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { source.value = "sequenceDiagram\n Alice->>Bob: 合成请求\n Bob-->>Alice: 合成响应" }
        compose.onNodeWithText("查看图表").performClick()
        val sequence = readyView()
        assertTrue(eval(sequence, "document.getElementById('preview').textContent.includes('合成响应')").toBoolean())
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(10_000) { views().isEmpty() }
    }

    @Test fun mermaidLinksHtmlAndStyleCannotCreateActiveContent() {
        language.value = "mermaid"
        source.value = "flowchart TD\n A[合成] --> B[结果]"
        fixture()
        compose.onNodeWithText("查看图表").performClick()
        val view = readyView()
        verifyLinkedDiagram(view)
        val attack =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 60" onload="window.hacked=true">
            <script>window.hacked=true</script><foreignObject><img src="https://example.invalid/image"/></foreignObject>
            <a href="javascript:alert(1)"><rect width="80" height="30" onclick="window.hacked=true"/></a>
            <image href="https://example.invalid/image"/><style>@import url(https://example.invalid/css);
            rect{fill:url(https://example.invalid/fill);stroke:#fff}</style></svg>
            """.trimIndent()
        val json =
            kotlinx.serialization.json
                .JsonPrimitive(attack)
                .toString()
        eval(view, "document.getElementById('preview').replaceChildren(sanitizeDiagramSvg($json));true")
        assertEquals(
            "0",
            eval(
                view,
                "document.querySelectorAll('#preview a,#preview image,#preview img,#previ" +
                    "ew foreignObject,#preview iframe,#preview script').length",
            ),
        )
        assertEquals("false", eval(view, "!!window.hacked"))
        assertEquals(
            "false",
            eval(
                view,
                "Array.from(document.querySelectorAll('#preview *')).some(n=>Array.from(n" +
                    ".attributes).some(a=>a.name.startsWith('on')||a.name==='href'&&!a.value." +
                    "startsWith('#')))",
            ),
        )
        assertEquals(
            "false",
            eval(
                view,
                "Array.from(document.querySelectorAll('#preview style')).some(n=>n.textCo" +
                    "ntent.includes('example.invalid'))",
            ),
        )
        assertEquals(
            "0",
            eval(
                view,
                "performance.getEntriesByType('resource').filter(r=>!r.name.startsWith('h" +
                    "ttps://appassets.androidplatform.net/assets/mermaid/')).length",
            ),
        )
        eval(view, "location.href='https://example.invalid/not-loaded';true")
        Thread.sleep(150)
        assertEquals(MermaidPreviewPolicy.PAGE, eval(view, "location.href").trim('"'))
        compose.onNodeWithText("关闭").performClick()
    }

    @Test fun mermaidConfigurationAndOversizedGraphsFailClearlyWithoutLosingSource() {
        language.value = "mermaid"
        source.value = "---\nconfig:\n securityLevel: loose\n---\nflowchart TD\n A-->B"
        fixture()
        compose.onNodeWithText("查看图表").performClick()
        compose.onNodeWithTag("block-preview-error").assertIsDisplayed()
        assertTrue(views().isEmpty())
        compose.onNodeWithText("关闭").performClick()
        for (text in listOf(
            "%%{init: {'securityLevel': 'loose'}}%%\nflowchart TD\n A-->B",
            "flowchart TD\n" + (0..128).joinToString("\n") { "N$it-->N${it + 1}" },
            "mindmap\n root((unsupported))",
            "x".repeat(8193),
        )) {
            compose.runOnIdle { source.value = text }
            compose.onNodeWithText("查看图表").performClick()
            compose.waitUntil(12_000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("block-preview-error"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("关闭").performClick()
            compose.waitUntil(10_000) { views().isEmpty() }
        }
    }

    @Test fun readinessTimeoutReportsErrorAndReleasesItsOwnedView() {
        require(context.packageName == "com.helix.agent.developer.asrvalidation")
        val visible = mutableStateOf(true)
        val failed =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        compose.setContent {
            if (visible.value) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        OfflinePreviewWebView(
                            ctx,
                            "<html><body data-state='loading'></body></html>",
                            BlockPreviewKind.MERMAID,
                            {},
                        ) { status ->
                            if (status == "error") {
                                failed.set(true)
                                visible.value = false
                            }
                        }.apply { loadUrl(MermaidPreviewPolicy.PAGE) }
                    },
                    onRelease = { it.release() },
                )
            }
        }
        compose.waitUntil(10_000) { failed.get() && views().isEmpty() }
    }

    @Test fun invalidOversizedAndRapidCancelledPreviewsKeepTheirSource() {
        source.value = "\\unknownCommand{"
        fixture()
        compose.onNodeWithText("查看公式").performClick()
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("block-preview-error"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("block-preview-error").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { source.value = "x".repeat(8193) }
        compose.onNodeWithText("查看公式").performClick()
        compose.onNodeWithTag("block-preview-error").assertIsDisplayed()
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
                        .hasTestTag("block-preview-error"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("block-preview-error").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.waitUntil(10_000) { views().isEmpty() }
    }

    private fun views(): List<OfflinePreviewWebView> {
        val found = mutableListOf<OfflinePreviewWebView>()

        fun visit(view: View) {
            if (view is OfflinePreviewWebView) found += view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return found
    }

    private fun verifyLinkedDiagram(view: OfflinePreviewWebView) {
        val linked =
            kotlinx.serialization.json
                .JsonPrimitive(
                    "flowchart TD\n A[合成] --> B[结果]\nclick B \"https://example.invalid/link\"",
                ).toString()
        eval(
            view,
            "document.body.dataset.probe='pending';" +
                "mermaid.render('linked-probe',$linked).then(r=>{try{sanitizeDiagramSvg(r.svg);" +
                "document.body.dataset.probe='ok'}catch(e){document.body.dataset.probe='svg:'+e.message}})" +
                ".catch(e=>document.body.dataset.probe='render:'+e.message);true",
        )
        compose.waitUntil(10_000) { eval(view, "document.body.dataset.probe") != "\"pending\"" }
        assertEquals("\"ok\"", eval(view, "document.body.dataset.probe"))
    }

    private fun readyView(): OfflinePreviewWebView {
        var result: OfflinePreviewWebView? = null
        compose.waitUntil(10_000) {
            val view = views().singleOrNull()
            if (view != null && eval(view, "document.body.dataset.state") == "\"ready\"" &&
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("block-preview-loading"),
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
        view: OfflinePreviewWebView,
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
        view: OfflinePreviewWebView,
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
