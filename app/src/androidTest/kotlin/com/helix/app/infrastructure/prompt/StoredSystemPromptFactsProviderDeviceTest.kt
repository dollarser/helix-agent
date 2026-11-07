package com.helix.app.infrastructure.prompt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.application.context.SystemPromptRequest
import com.helix.app.memory.MemoryService
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.mapping.StoredGoal
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MarkdownMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Room facts and actual MemoryService: no fake authorization or storage adapter. */
class StoredSystemPromptFactsProviderDeviceTest {
    @Test
    fun deniedReadsNeverEnterMemoryOrWorkspaceReaders() =
        withStorage { storage, root ->
            var memorySettingReads = 0
            var workspaceReads = 0
            val memory =
                MemoryService(
                    MarkdownMemoryStore(File(root, "memory").toPath()),
                    setting = {
                        memorySettingReads++
                        true
                    },
                    saveSetting = { _, _ -> },
                )
            val provider =
                StoredSystemPromptFactsProvider(
                    storage,
                    projectInstructionsReader = {
                        workspaceReads++
                        "Workspace instructions"
                    },
                    memory = memory,
                )
            val request = request(storage)
            for (rule in listOf(OperationRule.DENY, OperationRule.ASK)) {
                storage.sessionPermissionConfigs.setForSession(
                    SESSION,
                    SessionPermissionConfig.custom(
                        mapOf(OperationEffect.FILE_READ_EXTERNAL to rule, OperationEffect.FILE_READ_WORKSPACE to rule),
                    ),
                    2,
                )
                val facts = provider.collect(request)
                assertNull(facts.memory)
                assertEquals("", facts.projectInstructions)
                assertEquals(0, memorySettingReads)
                assertEquals(0, workspaceReads)
            }
            storage.sessionPermissionConfigs.setForSession(
                SESSION,
                SessionPermissionConfig.custom(
                    mapOf(
                        OperationEffect.FILE_READ_EXTERNAL to OperationRule.ALLOW,
                        OperationEffect.FILE_READ_WORKSPACE to OperationRule.ALLOW,
                    ),
                ),
                3,
            )
            val allowed = provider.collect(request)
            assertNotNull(allowed.memory)
            assertEquals("Workspace instructions", allowed.projectInstructions)
            assertEquals(1, memorySettingReads)
            assertEquals(1, workspaceReads)
        }

    @Test
    fun explicitActiveTurnKeepsItsGoalWhenAnotherTurnIsLatest() =
        withStorage { storage, _ ->
            bindGoal(storage, "original", "Original objective", 2)
            bindGoal(storage, "latest", "Latest objective", 3)
            assertEquals(
                "latest",
                storage.turns
                    .listBySession(SESSION)
                    .last()
                    .id,
            )
            val provider = StoredSystemPromptFactsProvider(storage, { "" })
            val original = requireNotNull(provider.collect(request(storage).copy(turnId = "original")).goal)
            assertEquals("original", original.turnId)
            assertEquals("run-original", original.runId)
            assertEquals("goal-original", original.goalId)
            assertEquals("Original objective", original.objective)
            val latest = requireNotNull(provider.collect(request(storage)).goal)
            assertEquals("latest", latest.turnId)
            assertEquals("Latest objective", latest.objective)
        }

    @Test
    fun turnFromAnotherSessionIsRejectedBeforeWorkspaceRead() =
        withStorage { storage, _ ->
            storage.sessions.create("other", "Other session", null, null, 1)
            storage.turns.start("other-turn", "other", 2)
            var workspaceReads = 0
            val provider =
                StoredSystemPromptFactsProvider(
                    storage,
                    projectInstructionsReader = {
                        workspaceReads++
                        "Workspace instructions"
                    },
                )
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    provider.collect(request(storage).copy(turnId = "other-turn"))
                }
            assertEquals("System prompt turn belongs to another session", error.message)
            assertEquals(0, workspaceReads)
        }

    private fun request(storage: HelixStorage) =
        SystemPromptRequest(
            SESSION,
            null,
            FileScopePath.fromModelReference(requireNotNull(storage.sessions.resolve(SESSION).directoryRef)),
            AgentMode.ACT,
            setOf("memory.read"),
        )

    private fun bindGoal(
        storage: HelixStorage,
        turn: String,
        objective: String,
        startedAt: Long,
    ) {
        storage.turns.start(turn, SESSION, startedAt)
        storage.goals.save(
            StoredGoal(
                id = "goal-$turn",
                objective = objective,
                criteria = emptyList(),
                budgets = GoalBudgets(maxGoalRounds = 256),
                state = "RUNNING",
                planId = null,
                planHash = null,
                nextCheckpoint = null,
                correlationId = "correlation-$turn",
                runCount = 1,
                modelCalls = 0,
                toolCalls = 0,
                totalTokens = 0,
                runTimeMillis = 0,
                currentWakeMillis = 0,
                lastWakeReason = null,
                error = null,
                finishReason = null,
            ),
        )
        storage.goalRuns.open("run-$turn", "goal-$turn", "USER_OPEN", startedAt)
        storage.goalTurnBindings.bind(turn, "run-$turn")
    }

    private fun withStorage(block: (HelixStorage, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "prompt-facts-${UUID.randomUUID()}"
        val root = File(context.cacheDir, id)
        val storage = HelixStorage.open(context, "$id.db", root)
        try {
            storage.sessions.create(SESSION, "Prompt facts", null, null, 1)
            block(storage, root)
        } finally {
            storage.close()
            context.deleteDatabase("$id.db")
            root.deleteRecursively()
        }
    }

    private companion object {
        const val SESSION = "prompt-session"
    }
}
