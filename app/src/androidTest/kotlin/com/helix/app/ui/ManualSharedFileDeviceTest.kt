package com.helix.app.ui

import android.os.Environment
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.helix.app.MainActivity
import com.helix.app.files.FileManagerService.FileOpResult
import com.helix.app.files.SharedStorageAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ManualSharedFileDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("LongMethod") // One user journey, including the explicit delete-confirmation boundary.
    @Test
    fun userCanManageSharedFilesWithoutProviderAndDeleteRequiresConfirmation() {
        compose.resetDeterministicUiState()
        val context = compose.activity
        assertTrue(SharedStorageAccess(context).isWritable())
        val name = "helix-manual-${UUID.randomUUID()}"

        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStorageDirectory(), name)
        try {
            assertTrue(folder.mkdir())
            folder.resolve("original.txt").writeText("user file")
            val manager = compose.container().fileManager
            val scope = SharedStorageAccess.SCOPE_ID
            val pipeline = compose.container().toolPipeline
            val descriptor =
                pipeline.registry.resolve(
                    com.helix.core.model
                        .ToolName("read"),
                    com.helix.core.model
                        .ToolVersion(1),
                )
            val result =
                pipeline.implementations.resolve(descriptor.name, descriptor.version).execute(
                    com.helix.tools.framework.ExecutableToolCall(
                        toolCallId = "manual-boundary",
                        toolName = "read",
                        toolVersion = "1",
                        args =
                            kotlinx.serialization.json.buildJsonObject {
                                put("path", kotlinx.serialization.json.JsonPrimitive("scope:$scope:$name/original.txt"))
                            },
                        executionTarget = com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                        deadline =
                            java.time.Instant
                                .now()
                                .plusSeconds(10),
                        cancel = com.helix.tools.framework.NoCancellation,
                    ),
                )
            assertTrue(result is com.helix.tools.framework.ToolExecutorResult.Failed)
            assertTrue(manager.makeDirectory(scope, name, "destination") is FileOpResult.Ok)
            assertTrue(
                manager.copy(scope, "$name/original.txt", "$name/destination/copy.txt", false) is FileOpResult.Ok,
            )
            assertEquals("user file", folder.resolve("destination/copy.txt").readText())
            compose.navigateTo("files")
            compose.onNodeWithTag("files-shared-open").performClick()
            compose.waitUntil { compose.onAllNodesWithTag("files-entry-$name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("files-entry-$name").performScrollTo().performClick()
            compose.waitUntil {
                compose
                    .onAllNodesWithTag(
                        "files-entry-original.txt",
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("files-entry-original.txt").performTouchInput { longClick() }
            compose.onNodeWithTag("files-batch-rename").performScrollTo().performClick()
            compose.onNodeWithTag("files-rename-field").performTextReplacement("renamed.txt")
            compose.onNodeWithTag("files-rename-confirm").performClick()
            compose.waitUntil {
                compose
                    .onAllNodesWithTag(
                        "files-entry-renamed.txt",
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("files-entry-renamed.txt").performTouchInput { longClick() }
            compose.onNodeWithTag("files-batch-trash").performScrollTo().performClick()
            assertTrue(folder.resolve("renamed.txt").exists())
            compose.onNodeWithTag("files-permanent-delete-confirm").performClick()
            compose.waitUntil { !folder.resolve("renamed.txt").exists() }
            assertFalse(folder.resolve("renamed.txt").exists())
        } finally {
            folder.deleteRecursively()
        }
    }
}
