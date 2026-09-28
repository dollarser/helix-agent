package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.memory.MemoryService
import com.helix.core.workspace.memory.MarkdownMemoryStore
import com.helix.core.workspace.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Compiled fixtures; execution requires explicit owner authorization for this task. */
class MemoryDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualSaveReopenAndConfirmedDeleteUseTheMarkdownStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "memory-ui-${UUID.randomUUID()}")
        val flags = mutableMapOf<String, Boolean>()
        val service =
            MemoryService(MarkdownMemoryStore(root.toPath()), { flags[it] ?: false }, { k, v -> flags[k] = v })
        try {
            compose.setContent { MaterialTheme { MemoryDialog(service, "session") {} } }
            compose.waitForIdle()
            compose.onNodeWithTag("memory-markdown").performScrollTo().performTextReplacement("Prefer concise answers")
            compose
                .onNodeWithTag("memory-save")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(10_000) { service.list(MemoryScope.Global).size == 1 }
            assertTrue(
                MarkdownMemoryStore(root.toPath()).read(MemoryScope.Global, "user.md").markdown.contains("concise"),
            )
            compose.onNodeWithTag("memory-delete").performScrollTo().performClick()
            compose.onNodeWithTag("memory-delete-confirm").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { service.list(MemoryScope.Global).isEmpty() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun conflictingSavePreservesTheDraftAndExternalEdit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "memory-conflict-${UUID.randomUUID()}")
        val service = MemoryService(MarkdownMemoryStore(root.toPath()), { false }, { _, _ -> })
        try {
            val original = service.save(MemoryScope.Global, "user.md", "initial", "new")
            compose.setContent { MaterialTheme { MemoryDialog(service, "session") {} } }
            compose.waitForIdle()
            compose.onNodeWithTag("memory-entry-user.md").performScrollTo().performClick()
            compose.waitForIdle()
            service.save(MemoryScope.Global, "user.md", "external change", original.hash)
            compose.onNodeWithTag("memory-markdown").performScrollTo().performTextReplacement("my draft")
            compose.onNodeWithTag("memory-save").performScrollTo().performClick()
            compose.onNodeWithTag("memory-error").performScrollTo().assertIsDisplayed()
            assertEquals("external change", service.read(MemoryScope.Global, "user.md").markdown)
        } finally {
            root.deleteRecursively()
        }
    }
}
