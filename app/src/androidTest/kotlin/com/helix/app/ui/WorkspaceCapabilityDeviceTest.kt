package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixTheme
import com.helix.app.files.FileManagerService
import com.helix.app.files.ManualFileOperations
import com.helix.app.files.NioManualFileBackend
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class WorkspaceCapabilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directoryCapabilityIsVisibleBeforeConfirmAndCancelDoesNotBindOrCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".asrvalidation"))
        val root = File(context.cacheDir, "capability-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(root, "original.txt").writeText("Synthetic unchanged original")
            val roots = ScopeRootResolver { root.toPath() }
            val backend = NioManualFileBackend("app", roots, false) {}
            val files =
                FileManagerService(
                    WorkspaceArtifactStore(roots),
                    roots,
                    "app",
                    manual = ManualFileOperations({ backend }, { true }),
                )
            var selected: String? = null
            var closed = false
            compose.setContent {
                HelixTheme {
                    SessionDirectoryDialog(files, { closed = true }) {
                        selected = it
                        true
                    }
                }
            }
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("session-directory-source-app"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("session-directory-source-app").performClick()
            compose.waitUntil(5000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("directory-access-capability"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("directory-access-capability").assertIsDisplayed()
            assertNull(selected)
            compose.onNodeWithTag("session-directory-cancel").performClick()
            assertEquals(true, closed)
            assertNull(selected)
            assertEquals(listOf("original.txt"), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }
}
