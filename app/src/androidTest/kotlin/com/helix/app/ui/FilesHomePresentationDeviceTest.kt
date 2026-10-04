package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.files.FileSource
import com.helix.app.files.FileSourceKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FilesHomePresentationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manyWorkspacesStayBehindOneEntryAndShortcutsUseDeviceFolders() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
        val container = app.appContainer
        val state = FilesScreenState(container.fileManager)
        val workspaces = (1..100).map { FileSource("ws-$it", "Session $it", FileSourceKind.WORKSPACE, true) }
        state.replaceSources(state.sources + workspaces)
        val requested = mutableListOf<String>()
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
                if (state.workspacesOpen) FilesWorkspaces(state) else FilesHome(state, actions) { requested += it }
            }
        }
        compose.onNodeWithTag("files-quick-Download").performScrollTo().performClick()
        compose.onNodeWithTag("files-quick-Documents").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("Download", "Documents"), requested) }
        compose.onNodeWithTag("files-home-source-ws-1").assertDoesNotExist()
        compose.onNodeWithTag("files-workspaces-open").performScrollTo().performClick()
        compose.onNodeWithTag("files-workspace-search").performTextReplacement("Session 100")
        compose.onNodeWithTag("files-workspace-ws-100").assertIsDisplayed()
        compose.onNodeWithTag("files-workspace-ws-1").assertDoesNotExist()
        compose.onNodeWithTag("files-home-open").performClick()
        compose.onNodeWithTag("files-workspaces-open").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(container.fileManager.defaultSource.scopeId, state.selectedScopeId) }
    }
}
