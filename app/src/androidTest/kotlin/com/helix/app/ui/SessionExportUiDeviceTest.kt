package com.helix.app.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.export.SessionExportFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Uses the OS activity-result boundary; document creation/readback uses a real granted provider. */
class SessionExportUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pickerCancelDoesNotStartExportAndChosenDocumentGetsRealCompletedOutput() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val sessionId = "export-ui-${UUID.randomUUID()}"
        container.storage.sessions.create(sessionId, "Export fixture", null, null, 1)
        container.storage.messages.append("$sessionId-message", sessionId, null, "USER", "TEXT", "synthetic UI body")
        SessionExportFixture().use { fixture ->
            try {
                container.chatService.openSession(sessionId)
                compose.waitUntil(10000) { container.chatService.screen.value.openSessionId == sessionId }
                compose.onNodeWithTag("chat-conversation-details").performClick()
                compose.onNodeWithTag("session-export-open").performClick()
                compose.waitUntil(10000) {
                    compose.onAllNodesWithTag("session-export-create").fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                val cancelled =
                    pickerResult(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)) {
                        compose.onNodeWithTag("session-export-create").assertIsEnabled().performClick()
                    }
                assertEquals(1, cancelled)
                assertTrue(fixture.temporaryIsEmpty())
                val target = fixture.create("normal")
                val chosen =
                    pickerResult(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(target))) {
                        compose.onNodeWithTag("session-export-create").performClick()
                    }
                assertEquals(1, chosen)
                compose.waitUntil(15000) {
                    compose.onAllNodesWithTag("session-export-notice").fetchSemanticsNodes().isNotEmpty()
                }
                compose
                    .onNodeWithTag("session-export-notice")
                    .assertTextContains(compose.activity.getString(R.string.session_export_complete))
                assertTrue(fixture.read(target).contains("synthetic UI body"))
                assertTrue(fixture.read(target).contains("\"type\":\"complete\""))
                compose.onNodeWithTag("session-export-dismiss").performClick()
            } finally {
                container.chatService.closeSession()
                runBlocking { requireNotNull(container.sessionExport).cleanupInterrupted() }
                container.storage.deleteSessionPermanently(sessionId)
            }
        }
    }

    private fun pickerResult(
        result: Instrumentation.ActivityResult,
        action: () -> Unit,
    ): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter =
            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("application/x-ndjson")
            }
        val monitor = instrumentation.addMonitor(filter, result, true)
        return try {
            action()
            compose.waitForIdle()
            monitor.hits
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
