package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.application.context.SystemPromptContext
import com.helix.app.application.context.SystemPromptRequest
import com.helix.app.infrastructure.prompt.StoredSystemPromptFactsProvider
import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.app.memory.MemoryService
import com.helix.app.projects.ProjectService
import com.helix.core.agent.TrustLevel
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MarkdownMemoryStore
import com.helix.core.workspace.memory.MemoryScope
import com.helix.core.workspace.memory.ProjectMemoryScopeKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class MemoryPromptDeviceTest {
    @Test
    @Suppress("LongMethod") // Keep both project identities, production prompt checks and fixture cleanup together.
    fun projectsSharingADirectoryDoNotShareMemoryOrInstructions(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val id = "project-memory-prompt-${UUID.randomUUID()}"
            val root = File(context.cacheDir, id)
            val storage = HelixStorage.open(context, "$id.db", root)
            try {
                val projects = ProjectService(storage) { it }
                val first = projects.save(null, "First", "", "FIRST_INSTRUCTIONS", null)
                val record = requireNotNull(storage.projects.find(first))
                val shared = FileScopePath(record.workspaceId, record.relativePath).toModelReference()
                val second = projects.save(null, "Second", "", "SECOND_INSTRUCTIONS", shared)
                val a =
                    com.helix.app.projects
                        .createProjectSessionFixture(storage, first, null, null)
                val b =
                    com.helix.app.projects
                        .createProjectSessionFixture(storage, second, null, null)
                val memory =
                    MemoryService(
                        MarkdownMemoryStore(File(root, "memory").toPath()),
                        { it == "enabled" },
                        {
                            _,
                            _,
                            ->
                        },
                        project = { session ->
                            storage.projects
                                .forSession(session)
                                ?.id
                                ?.let(::ProjectMemoryScopeKey)
                        },
                    )
                memory.save(
                    MemoryScope.Project(ProjectMemoryScopeKey(first)),
                    "memory_summary.md",
                    "FIRST_MEMORY",
                    "new",
                )
                memory.save(
                    MemoryScope.Project(ProjectMemoryScopeKey(second)),
                    "memory_summary.md",
                    "SECOND_MEMORY",
                    "new",
                )
                val builder = SystemPromptContext(packagedPromptTemplates)
                val facts = StoredSystemPromptFactsProvider(storage, { "" }, memory)
                listOf(a, b).forEach { session ->
                    storage.sessionPermissionConfigs.setForSession(
                        session,
                        SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                        2,
                    )
                }
                val directory = FileScopePath.fromModelReference(shared)
                val requestA = SystemPromptRequest(a, null, directory, AgentMode.ACT, setOf("memory.read"))
                val requestB = SystemPromptRequest(b, null, directory, AgentMode.ACT, setOf("memory.read"))
                val promptA = builder.build(requestA, facts.collect(requestA))
                val promptB = builder.build(requestB, facts.collect(requestB))
                assertTrue(promptA.content.contains("FIRST_MEMORY"))
                assertTrue(promptA.content.contains("FIRST_INSTRUCTIONS"))
                assertFalse(promptA.content.contains("SECOND_MEMORY"))
                assertFalse(promptA.content.contains("SECOND_INSTRUCTIONS"))
                assertTrue(promptB.content.contains("SECOND_MEMORY"))
                assertFalse(promptB.content.contains("FIRST_MEMORY"))
                assertEquals(
                    TrustLevel.UNTRUSTED,
                    promptA.sections.single { it.name == "memory.summary.project" }.trust,
                )
                assertEquals(storage.sessions.resolve(a).directoryRef, storage.sessions.resolve(b).directoryRef)
            } finally {
                storage.close()
                context.deleteDatabase("$id.db")
                root.deleteRecursively()
            }
        }

    @Test fun productionPromptHonorsReadPermissionAndRecordsMemoryAsUntrusted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "memory-prompt-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, id)
        val storage = HelixStorage.open(context, "$id.db", directory)
        val flags = mutableMapOf("enabled" to true)
        val service =
            MemoryService(
                MarkdownMemoryStore(File(directory, "memory").toPath()),
                { flags[it] ?: false },
                { k, v -> flags[k] = v },
            )
        try {
            storage.sessions.create("session", "Memory", null, null, 1)
            service.save(MemoryScope.Global, "memory_summary.md", "MEMORY_FIXTURE_CONTENT", "new")
            val path =
                FileScopePath.fromModelReference(
                    requireNotNull(storage.sessions.resolve("session").directoryRef),
                )
            val builder = SystemPromptContext(packagedPromptTemplates)
            val facts = StoredSystemPromptFactsProvider(storage, { "" }, service)
            val request = SystemPromptRequest("session", null, path, AgentMode.ACT, setOf("memory.read"))
            assertFalse(
                builder
                    .build(request, facts.collect(request))
                    .content
                    .contains("MEMORY_FIXTURE_CONTENT"),
            )
            storage.sessionPermissionConfigs.setForSession(
                "session",
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                2,
            )
            val enabled = builder.build(request, facts.collect(request))
            assertTrue(enabled.content.contains("MEMORY_FIXTURE_CONTENT"))
            assertEquals(TrustLevel.UNTRUSTED, enabled.sections.single { it.name == "memory.summary.global" }.trust)
            flags["enabled"] = false
            assertFalse(
                builder
                    .build(request, facts.collect(request))
                    .content
                    .contains("MEMORY_FIXTURE_CONTENT"),
            )
        } finally {
            storage.close()
            context.deleteDatabase("$id.db")
            directory.deleteRecursively()
        }
    }
}
