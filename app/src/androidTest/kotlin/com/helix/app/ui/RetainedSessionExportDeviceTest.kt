package com.helix.app.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.export.SessionExportFixture
import com.helix.app.export.SessionExportService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Own database, documents, preferences and cache: never touches a user's pending export. */
class RetainedSessionExportDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("LongMethod") // Picker cancellation and granted-document delivery share one isolated export fixture.
    @Test
    fun jsonlPickerCancelWritesNothingAndChosenDocumentGetsCompletedExport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = "export-ui-${UUID.randomUUID()}"
        val cache = File(context.cacheDir, marker)
        val serviceContext =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this

                override fun getCacheDir(): File = cache

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ) = context.getSharedPreferences("$marker-$name", mode)
            }
        SessionExportFixture().use { fixture ->
            fixture.seed()
            val service = SessionExportService(serviceContext, fixture.storage.sessionExports, "fixture")
            val visible = mutableStateOf(true)
            try {
                compose.setContent {
                    MaterialTheme {
                        if (visible.value) SessionExportDialog("selected", service) { visible.value = false }
                    }
                }
                compose.waitUntil(
                    5000,
                ) { compose.onAllNodesWithTag("session-export-create").fetchSemanticsNodes().isNotEmpty() }
                val cancelled =
                    picker(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)) {
                        compose.onNodeWithTag("session-export-create").assertIsEnabled().performClick()
                    }
                assertEquals(1, cancelled)
                assertTrue(File(cache, "session-exports").listFiles().orEmpty().isEmpty())
                val target = fixture.create("normal")
                val chosen =
                    picker(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(target))) {
                        compose.onNodeWithTag("session-export-create").performClick()
                    }
                assertEquals(1, chosen)
                compose.waitUntil(10000) {
                    compose.onAllNodesWithTag("session-export-notice").fetchSemanticsNodes().isNotEmpty()
                }
                val body = fixture.read(target)
                assertTrue(body.contains("message 0"))
                assertTrue(body.contains("\"type\":\"complete\""))
                compose.onNodeWithTag("session-export-dismiss").performClick()
                assertTrue(context.getSharedPreferences("$marker-session-export", Context.MODE_PRIVATE).all.isEmpty())
            } finally {
                context.deleteSharedPreferences("$marker-session-export")
                cache.deleteRecursively()
            }
        }
    }

    private fun picker(
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
