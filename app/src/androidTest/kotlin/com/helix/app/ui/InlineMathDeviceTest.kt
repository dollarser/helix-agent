package com.helix.app.ui

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic rendering only, isolated package; no model, microphone, account or settings access. */
class InlineMathDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeInlineMathRendersReleasesAndSurvivesNarrowDarkRecomposition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName == "com.helix.agent.developer.asrvalidation")
        InlineDiagramCache.clear()
        var dark by mutableStateOf(false)
        var shown by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, if (dark) 1.5f else 1f),
                ) {
                    Column(
                        Modifier
                            .width(if (dark) 220.dp else 350.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .systemBarsPadding(),
                    ) {
                        if (shown) {
                            MarkdownText(
                                "Synthetic energy \\(E=mc^2\\), and \$x+y\$ stays in this paragraph.",
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        ready()
        save(context, "inline-math-light.png")
        compose.onAllNodes(hasTestTag("inline-math-image"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("block-preview-dialog")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("block-preview-close").performClick()
        compose.runOnIdle { dark = true }
        ready()
        save(context, "inline-math-dark-large.png")
        compose.runOnIdle { shown = false }
        compose.waitUntil(10_000) { InlineDiagramCache.renderer.availablePermits == 1 }
        assertEquals(1, InlineDiagramCache.renderer.availablePermits)
        assertTrue(InlineDiagramCache.retainedBytes <= 16 * 1024 * 1024)
    }

    private fun save(
        context: Context,
        name: String,
    ) {
        val bounds = compose.onNodeWithTag("inline-math-paragraph").fetchSemanticsNode().boundsInWindow
        assertTrue("Inline paragraph must occupy native layout space", bounds.width > 0 && bounds.height > 0)
        val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val image =
            android.graphics.Bitmap.createBitmap(
                screen,
                bounds.left.toInt().coerceAtLeast(0),
                bounds.top.toInt().coerceAtLeast(0),
                bounds.width.toInt().coerceAtMost(screen.width - bounds.left.toInt()),
                bounds.height.toInt().coerceAtMost(screen.height - bounds.top.toInt()),
            )
        File(context.cacheDir, name).outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        screen.recycle()
    }

    private fun ready() {
        compose.waitUntil(15_000) {
            compose
                .onAllNodes(hasTestTag("inline-math-image"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == 2 && InlineDiagramCache.renderer.availablePermits == 1
        }
    }
}
