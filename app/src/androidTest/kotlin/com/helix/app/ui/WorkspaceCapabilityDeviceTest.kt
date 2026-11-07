package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.files.FileManagerService
import com.helix.app.files.ManualFileOperations
import com.helix.app.files.NioManualFileBackend
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.ui.shared.HelixTheme
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
        val root = File(context.noBackupFilesDir, "capability-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(root, "original.txt").writeText("Synthetic unchanged original")
            val roots = ScopeRootResolver { root.toPath() }
            val backend = NioManualFileBackend("app", roots, false) {}
            val loads =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val files =
                FileManagerService(
                    WorkspaceArtifactStore(roots),
                    roots,
                    "app",
                    manual = ManualFileOperations({ backend }, { true }),
                    workspaceSources = {
                        loads.incrementAndGet()
                        emptyList()
                    },
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
            awaitNode("session-directory-source-app")
            settingsRoundTrip(context, loads)
            compose.onNodeWithTag("session-directory-source-app").performScrollTo().performClick()
            awaitNode("directory-access-capability")
            compose.onNodeWithTag("directory-access-capability").performScrollTo().assertIsDisplayed()
            assertNull(selected)
            compose.onNodeWithTag("session-directory-cancel").performClick()
            assertEquals(true, closed)
            assertNull(selected)
            assertEquals(listOf("original.txt"), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun awaitNode(tag: String) {
        compose.waitUntil(5000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(tag),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun settingsRoundTrip(
        context: android.content.Context,
        loads: java.util.concurrent.atomic.AtomicInteger,
    ) {
        compose.onNodeWithTag("workspace-storage-permission").assertIsDisplayed()
        val beforeGrant =
            com.helix.app.files
                .SharedStorageAccess(context)
                .isGranted()
        val beforeLoads = loads.get()
        compose.onNodeWithTag("workspace-storage-permission").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.nanoTime() + 5_000_000_000L
        while (automation.rootInActiveWindow?.packageName != "com.android.settings" && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("com.android.settings", automation.rootInActiveWindow?.packageName?.toString())
        org.junit.Assert.assertTrue(
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),
        )
        compose.waitUntil(5000) { loads.get() > beforeLoads }
        assertEquals(
            beforeGrant,
            com.helix.app.files
                .SharedStorageAccess(context)
                .isGranted(),
        )
    }
}
