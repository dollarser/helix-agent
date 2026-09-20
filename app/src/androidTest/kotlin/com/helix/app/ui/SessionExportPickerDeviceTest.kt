package com.helix.app.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real DocumentsUI, a newly created local Downloads file, and independent shell readback on an owned AVD. */
class SessionExportPickerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun realSystemPickerExportsReadableFileWithReachableActions() {
        compose.resetDeterministicUiState()
        verifyRequestedConfiguration()
        val container = compose.container()
        val id = "picker-${UUID.randomUUID()}"
        val filename = "hxa211-$id.jsonl"
        val path = "/sdcard/Download/$filename"
        container.storage.sessions.create(id, "Picker fixture", null, null, 1)
        container.storage.messages.append("$id-message", id, null, "USER", "TEXT", "synthetic picker body")
        try {
            container.chatService.openSession(id)
            compose.waitUntil(10000) { container.chatService.screen.value.openSessionId == id }
            compose.onNodeWithTag("chat-conversation-details").performClick()
            compose.onNodeWithTag("session-export-open").performScrollTo().performClick()
            compose.onNodeWithTag("session-export-create").assertIsDisplayed()
            compose.onNodeWithTag("session-export-dismiss").assertIsDisplayed()
            saveScreenshot("session-export-dialog")
            compose.onNodeWithTag("session-export-create").performClick()
            compose.waitUntil(15000) { node { it.isEditable } != null && isDocumentsUi() }
            saveScreenshot("session-export-picker")
            val edit = requireNotNull(node { it.isEditable })
            assertTrue(
                edit.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename)
                    },
                ),
            )
            val save =
                requireNotNull(
                    node {
                        it.isClickable && it.isEnabled && (it.text?.toString()?.lowercase() in setOf("save", "保存"))
                    },
                ) { "DocumentsUI save action not found" }
            assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15000) { !isDocumentsUi() }
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("session-export-notice").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithTag("session-export-notice")
                .performScrollTo()
                .assertTextContains(compose.activity.getString(R.string.session_export_complete))
            compose.onNodeWithTag("session-export-dismiss").assertIsDisplayed()
            saveScreenshot("session-export-completed")
            val result = shell("cat $path")
            assertTrue(result.contains("synthetic picker body"))
            assertTrue(result.contains("\"type\":\"complete\""))
            File(instrumentation.targetContext.filesDir, "session-export-picker.jsonl").writeText(result)
            compose.onNodeWithTag("session-export-dismiss").performClick()
        } finally {
            shell("rm -f $path")
            container.chatService.closeSession()
            runBlocking { requireNotNull(container.sessionExport).cleanupInterrupted() }
            container.storage.deleteSessionPermanently(id)
        }
    }

    private fun verifyRequestedConfiguration() {
        if (InstrumentationRegistry.getArguments().getString("expectedNarrow") == "true") {
            val configuration = compose.activity.resources.configuration
            assertTrue(configuration.screenWidthDp <= 360)
            assertTrue(configuration.fontScale >= 1.5f)
        }
    }

    private fun isDocumentsUi(): Boolean = automation.rootInActiveWindow?.packageName?.contains("documentsui") == true

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.let { find(it, predicate, 0) }

    private fun find(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        depth: Int,
    ): AccessibilityNodeInfo? =
        when {
            predicate(node) -> {
                node
            }

            depth >= 32 -> {
                null
            }

            else -> {
                (0 until node.childCount).firstNotNullOfOrNull { index ->
                    node.getChild(index)?.let { child -> find(child, predicate, depth + 1) }
                }
            }
        }

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(it)
                .bufferedReader()
                .use { reader -> reader.readText() }
        }

    private fun saveScreenshot(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
