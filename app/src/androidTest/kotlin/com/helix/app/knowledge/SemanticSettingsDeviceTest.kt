package com.helix.app.knowledge

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic rows only; cancellation must not change real semantic preferences or start indexing. */
class SemanticSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelAndDocumentControlsAreReachableAndCancelDoesNotSave() =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("helix.semanticSettingsLive")
            org.junit.Assume.assumeTrue("Explicit account-backed UI opt-in absent", flag != null)
            require(flag == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = context.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer")
            val store = KnowledgeStore(KnowledgeFixtureDocuments(), KnowledgeFixtureDocuments())
            store.saveNote(KnowledgeNote("synthetic", "Synthetic settings fixture", "Synthetic only", 1, 1))
            val service = KnowledgeService(app, store = store)
            val before = service.semantic.configuration()
            var dismissed = false
            try {
                compose.setContent { MaterialTheme { KnowledgeSemanticDialog(service) { dismissed = true } } }
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasText("Qwen/Qwen3-Embedding-0.6B")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText("Qwen/Qwen3-Embedding-0.6B")).assertIsDisplayed()
                compose.onNode(hasText("Synthetic settings fixture")).assertIsDisplayed()
                captureDialog(app)
                compose.onNode(hasText("模型参数") or hasText("Model parameters")).performClick()
                compose.onNode(hasText("关闭") or hasText("Close")).performClick()
                assertTrue(dismissed)
                assertEquals(before, service.semantic.configuration())
            } finally {
                service.close()
            }
        }

    private fun captureDialog(app: HelixApplication) {
        var window: android.view.Window? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun visit(view: android.view.View) {
                if (view is androidx.compose.ui.window.DialogWindowProvider) window = view.window
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            android.view.inspector.WindowInspector
                .getGlobalWindowViews()
                .forEach(::visit)
        }
        val dialog = requireNotNull(window)
        val bitmap = androidx.core.graphics.createBitmap(dialog.decorView.width, dialog.decorView.height)
        val copied = java.util.concurrent.CountDownLatch(1)
        var result = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            android.view.PixelCopy.request(
                dialog,
                bitmap,
                { value ->
                    result = value
                    copied.countDown()
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
        assertTrue(copied.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, result)
        java.io.File(app.cacheDir, "semantic-settings.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
