package com.helix.app.knowledge

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.app.ui.stableChatLayoutScreenshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual shell, synthetic private data, no provider calls or system file picker. */
class KnowledgeShellDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionShellShowsLibraryAndMarkdownWithoutChangingSavedContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.helix.agent.developer.asrvalidation")
        compose.resetDeterministicUiState()
        val service = (compose.activity.application as HelixApplication).knowledge
        val token = UUID.randomUUID().toString()
        var sourceId: String? = null
        var noteId: String? = null
        try {
            runBlocking {
                service
                    .capture(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "合成资料 · 真实应用导航验证\n$token"),
                        "模拟器测试",
                    ).join()
                sourceId =
                    service.items
                        .first { rows -> rows.any { token in it.rawText } }
                        .first { token in it.rawText }
                        .id
                noteId = service.noteService.createEmptyTestNote("合成 Markdown 笔记")
                service.noteService.save(
                    requireNotNull(service.noteService.get(requireNotNull(noteId))),
                    "合成 Markdown 笔记",
                    "# 阅读记录\n\n**这是合成内容。**\n\n- 原件独立保存\n- 只有用户确认后才发送",
                    true,
                )
                service.openRequests.value = null
            }
            openLibrary()
            compose.onNodeWithTag("knowledge-search").assertIsDisplayed()
            capture("shell-sources")
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("knowledge-note-$noteId")).fetchSemanticsNodes().isNotEmpty()
            }
            capture("shell-notes")
            compose.onNodeWithTag("knowledge-note-$noteId").performClick()
            compose.onNodeWithTag("knowledge-note-editor").performScrollToNode(hasTestTag("knowledge-note-preview"))
            compose.onNodeWithTag("knowledge-note-preview").assertIsDisplayed()
            capture("shell-reading")
            compose.onNodeWithTag("knowledge-note-back").performClick()
            assertEquals("REVIEWED", runBlocking { service.noteService.get(requireNotNull(noteId))?.state })
        } finally {
            runBlocking {
                noteId?.let { id -> service.noteService.get(id)?.let { service.noteService.management.remove(it) } }
                sourceId?.let { id ->
                    service.items
                        .first()
                        .firstOrNull { it.id == id }
                        ?.let { service.remove(id, it) }
                }
            }
        }
    }

    @Test
    fun actualActivityRecreationKeepsSearchAndUnsavedMarkdown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.helix.agent.developer.asrvalidation")
        compose.resetDeterministicUiState()
        val service = (compose.activity.application as HelixApplication).knowledge
        val savedBody = "# Saved synthetic body"
        val id =
            runBlocking {
                service.noteService.createEmptyTestNote("Rebuild synthetic note").also {
                    service.noteService.save(
                        requireNotNull(service.noteService.get(it)),
                        "Rebuild synthetic note",
                        savedBody,
                        true,
                    )
                }
            }
        try {
            openLibrary()
            compose.onNodeWithTag("knowledge-tab-notes").performClick()
            compose.onNodeWithTag("knowledge-search").performTextReplacement("Rebuild")
            val first = compose.activity
            compose.activityRule.scenario.recreate()
            assertNotSame(first, compose.activity)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("knowledge-note-$id")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-search").assertTextContains("Rebuild")
            compose.onNodeWithTag("knowledge-note-$id").performClick()
            compose.onNodeWithTag("knowledge-note-toggle-mode").assertIsDisplayed().performClick()
            compose.onNodeWithTag("knowledge-note-body").performScrollTo()
            compose.onNodeWithTag("knowledge-note-body").performTextReplacement("## Unsaved draft retained")
            val second = compose.activity
            compose.activityRule.scenario.recreate()
            assertNotSame(second, compose.activity)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("knowledge-note-editor")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("knowledge-note-body").performScrollTo()
            compose.onNodeWithTag("knowledge-note-body").assertTextContains("## Unsaved draft retained")
            assertEquals(savedBody, runBlocking { service.noteService.get(id)?.body })
            assertEquals("REVIEWED", runBlocking { service.noteService.get(id)?.state })
            capture("shell-recreated-draft")
        } finally {
            runBlocking { service.noteService.get(id)?.let { service.noteService.management.remove(it) } }
        }
    }

    private fun openLibrary() {
        compose.navigateTo("knowledge")
        compose.waitForIdle()
        if (compose.onAllNodes(hasTestTag("knowledge-back")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("knowledge-back").performClick()
        }
    }

    private fun capture(name: String) {
        val profile = InstrumentationRegistry.getArguments().getString("knowledgeUiCapture") ?: return
        require(profile.matches(Regex("[a-z0-9-]{1,30}")))
        compose.waitForIdle()
        val bitmap = stableChatLayoutScreenshot()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "knowledge-ui-captures")
        check(root.isDirectory || root.mkdirs())
        try {
            File(root, "$profile-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
