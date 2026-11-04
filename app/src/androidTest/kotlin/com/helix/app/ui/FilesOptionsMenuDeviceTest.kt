package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.files.SortKey
import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class FilesOptionsMenuDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun capturesRootAndOrderMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HelixApplication
        val container = app.appContainer
        compose.setContent {
            MaterialTheme {
                FilesScreen(
                    container.fileManager,
                    container.safTree,
                    container.featureFiles,
                    initialDirectory = FileScopePath("app", "work"),
                )
            }
        }
        compose.onNodeWithTag("files-controls-open").performClick()
        capture("files-options-root.png")
        compose.onNodeWithTag("files-sort-open").performScrollTo().performClick()
        capture("files-options-order.png")
        compose.onNodeWithTag("files-controls-close").performClick()
    }

    @Test fun choicePagesKeepSelectionAndReturnWithoutChangingDirectory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as HelixApplication
        val container = app.appContainer
        val state =
            FilesScreenState(container.fileManager).apply {
                openLocation("app", "work")
                sources = sources.map { it.copy(cleanupEligible = true) }
            }
        compose.setContent {
            MaterialTheme {
                val actions =
                    FilesScreenActions(
                        state,
                        container.fileManager,
                        container.safTree,
                        container.featureFiles,
                        rememberCoroutineScope(),
                        app,
                        app.resources,
                    )
                FilesLocationBar(state, actions)
            }
        }
        compose.onNodeWithTag("files-controls-open").performClick()
        compose.onNodeWithTag("files-sort-open").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("files-sort-NAME").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("files-newfile").assertDoesNotExist()
        compose.onNodeWithTag("files-options-back").performClick()
        compose.onNodeWithTag("files-sort-open").assertIsDisplayed()
        compose.onNodeWithTag("files-refresh").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("files-controls-close").performClick()
        chooseAllSortAndViewOptions(state)
        compose.onNodeWithTag("files-controls-open").performClick()
        compose
            .onNodeWithTag(
                "files-view-open",
            ).assertTextContains(app.getString(com.helix.app.R.string.files_view_grid))
        compose.onNodeWithTag("files-view-open").performClick()
        compose.onNodeWithTag("files-view-grid").assertIsSelected()
        instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("files-sort-open").assertIsDisplayed() }.isSuccess
        }
        instrumentation.uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        )
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("files-options-dialog").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle {
            assertEquals("work", state.currentPath)
            assertEquals("app", state.selectedScopeId)
        }
    }

    private fun chooseAllSortAndViewOptions(state: FilesScreenState) {
        SortKey.entries.forEach { key ->
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-sort-open").performClick()
            compose.onNodeWithTag("files-sort-${key.name}").performClick()
            compose.runOnIdle {
                assertEquals(key, state.sortKey)
                assertFalse(state.controlsOpen)
            }
        }
        ViewMode.entries.forEach { mode ->
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-view-open").performClick()
            compose.onNodeWithTag("files-view-${mode.name.lowercase()}").assertHeightIsAtLeast(48.dp).performClick()
            compose.runOnIdle {
                assertEquals(mode, state.viewMode)
                assertFalse(state.controlsOpen)
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.SystemClock.sleep(300) // Wait for the platform dialog-window transition before capturing pixels.
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        instrumentation.targetContext.cacheDir.resolve(name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
