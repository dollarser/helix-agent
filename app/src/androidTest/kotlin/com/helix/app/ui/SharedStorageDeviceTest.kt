package com.helix.app.ui

import android.os.Build
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import com.helix.app.files.SharedStorageAccess
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class SharedStorageDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun grantedRootNavigationKeepsAgentScopeSeparate() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        // Device-state fixture: the system all-files flag must be GRANTED
        // (`adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`) — the sibling test
        // pins the OPPOSITE state, so each test guards its own configuration (one
        // run exercises exactly one; the other is covered by a run in that state).
        val access = SharedStorageAccess(compose.activity)
        assumeTrue(
            "all-files access must be granted (appops set MANAGE_EXTERNAL_STORAGE allow)",
            access.isGranted(),
        )
        compose.resetDeterministicUiState()
        val folderName = "helix-root-${UUID.randomUUID()}"

        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStorageDirectory(), folderName)
        try {
            assertTrue(folder.mkdir())
            folder.resolve("readme.txt").writeText("shared storage fixture")
            verifyAgentCannotReadManualRoot(folderName)
            compose.navigateTo("files")
            compose.onNodeWithTag("files-shared-open").performClick()
            compose.onNodeWithTag("files-entry-$folderName").performScrollTo().assertIsDisplayed()
            val manager = compose.container().fileManager
            assertTrue(manager.sources().single { it.scopeId == SharedStorageAccess.SCOPE_ID }.supportsMutation)
            assertThrows(Exception::class.java) { manager.list(SharedStorageAccess.SCOPE_ID, "../") }
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun verifyAgentCannotReadManualRoot(folder: String) {
        val pipeline = compose.container().toolPipeline
        val descriptor = pipeline.registry.resolve(ToolName("read"), ToolVersion(1))
        val result =
            pipeline.implementations.resolve(descriptor.name, descriptor.version).execute(
                ExecutableToolCall(
                    toolCallId = "manual-root-boundary",
                    toolName = "read",
                    toolVersion = "1",
                    args = buildJsonObject { put("path", "scope:${SharedStorageAccess.SCOPE_ID}:$folder/readme.txt") },
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    deadline = Instant.now().plusSeconds(10),
                    cancel = NoCancellation,
                ),
            )
        assertTrue(result is ToolExecutorResult.Failed)
    }

    @Test fun deniedRootHasNoReadableSource() {
        // Opposite device state: the all-files flag must be DENIED (a fresh install —
        // the grant is only set from the system settings / appops, tests never flip it).
        val access = SharedStorageAccess(compose.activity)
        assumeTrue(
            "all-files access must be denied (fresh install, no appops grant)",
            !access.isGranted(),
        )
        val manager = compose.container().fileManager
        assertThrows(Exception::class.java) { manager.list(SharedStorageAccess.SCOPE_ID, "") }
        assertFalse(manager.sources().any { it.scopeId == SharedStorageAccess.SCOPE_ID })
    }
}
