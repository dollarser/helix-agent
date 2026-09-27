package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.FileToolArguments
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.FileScopePath
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class WorkspaceJobDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Suppress("LongMethod") // One real Job crosses launch, binding switch, denial and original result collection.
    @Test
    fun runningJobKeepsOriginalInputAndOutputAndRechecksDenial() {
        DetachedJobJourneyFixture(app).use { f ->
            val next = f.storage.sessions.create("next-${f.id}", "Next", null, null, 1)
            val nextId = requireNotNull(f.storage.workspaces.binding(next.id)).workspaceId
            val nextRoot = f.storage.workspaces.managedDirectory(nextId)
            nextRoot.resolve("input.txt").toFile().writeText("wrong directory")
            f.start("sleep 2; cat /workspace/input.txt > result.txt") { args ->
                val id = requireNotNull(f.storage.workspaces.binding(f.id)).workspaceId
                check(
                    f.storage.workspaces
                        .managedDirectory(id)
                        .resolve("output")
                        .toFile()
                        .mkdir(),
                )
                f.storage.workspaces
                    .managedDirectory(id)
                    .resolve("input.txt")
                    .toFile()
                    .writeText("original snapshot")
                FileToolArguments.normalize(
                    JsonObject(
                        args +
                            mapOf(
                                "files" to JsonArray(listOf(JsonPrimitive("input.txt"))),
                                "output" to JsonPrimitive("output/result.txt"),
                            ),
                    ),
                    FileScopePath(id, ""),
                )
            }
            val originalId = requireNotNull(f.storage.workspaces.binding(f.id)).workspaceId
            val root = f.storage.workspaces.managedDirectory(originalId)
            val result = root.resolve("output/result.txt").toFile()
            root.resolve("input.txt").toFile().writeText("changed after launch")
            f.storage.sessions.updateDetails(f.id, f.job.title, next.directoryRef)
            assertThrows(IllegalStateException::class.java) { f.container.fileManager.cleanupWorkspace(originalId) }
            assertEquals(ProotJobState.SUCCEEDED, f.awaitTerminal().state)
            f.storage.sessions.updateDetails(f.id, f.job.title, "scope:$originalId:")
            deny(f, OperationEffect.FILE_MUTATION_WORKSPACE, OperationEffect.FILE_MUTATION_EXTERNAL)
            assertEquals(BackgroundJobActionOutcome.FAILED, collect(f))
            assertFalse(result.exists())
            f.storage.sessions.updateDetails(f.id, f.job.title, next.directoryRef)
            deny(f, OperationEffect.FILE_MUTATION_EXTERNAL, OperationEffect.FILE_MUTATION_WORKSPACE)
            assertEquals(BackgroundJobActionOutcome.FAILED, collect(f))
            assertFalse(result.exists())
            assertFalse(nextRoot.resolve("output/result.txt").toFile().exists())
            f.permission(SessionPermissionMode.FULL_ACCESS)
            assertEquals(BackgroundJobActionOutcome.SETTLED, collect(f))
            assertEquals("original snapshot", result.readText())
            assertFalse(nextRoot.resolve("output/result.txt").toFile().exists())
            assertEquals(
                1,
                f.storage.toolCalls
                    .listByTurn(f.id)
                    .size,
            )
            f.storage.sessions.archive(next.id, System.currentTimeMillis())
        }
    }

    private fun collect(f: DetachedJobJourneyFixture) =
        ProotToolModule.performBackgroundJobAction(f.job, BackgroundJobAction.COLLECT) { false }

    private fun deny(
        f: DetachedJobJourneyFixture,
        denied: OperationEffect,
        allowed: OperationEffect,
    ) {
        f.storage.sessionPermissionConfigs.setForSession(
            f.id,
            SessionPermissionConfig.custom(mapOf(denied to OperationRule.DENY, allowed to OperationRule.ALLOW)),
            System.currentTimeMillis(),
        )
    }
}
