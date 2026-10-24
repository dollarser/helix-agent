package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.application.context.SystemPromptContext
import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.app.memory.MemoryService
import com.helix.app.projects.ProjectService
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MarkdownMemoryStore
import com.helix.core.workspace.memory.MemoryScope
import com.helix.core.workspace.memory.ProjectMemoryScopeKey
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic isolated memory and production prompt builder; existing authorized Provider only. */
class LiveMemoryRecallDeviceTest {
    @Test
    @Suppress("LongMethod") // Keep isolated database lifetime and four independent request assertions together.
    fun recallRespectsProjectIdentityAndReadPermission(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("liveJob") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val providerId = requireNotNull(args.getString("liveProviderId"))
            val model = requireNotNull(args.getString("liveModel"))
            require(
                c.storage.providerConfigs
                    .resolve(providerId)
                    .model == model,
            )
            val provider = c.providerService.modelProviderFor(providerId, model)
            val name = "live-memory-${UUID.randomUUID()}"
            val root = File(app.cacheDir, name)
            val storage = HelixStorage.open(app, "$name.db", root)
            try {
                val projects = ProjectService(storage) { it }
                val first = projects.save(null, "First", "", "", null)
                val second = projects.save(null, "Second", "", "", null)
                val memory =
                    MemoryService(
                        MarkdownMemoryStore(File(root, "memory").toPath()),
                        { it == "enabled" },
                        { _, _ -> },
                        project = {
                            storage.projects
                                .forSession(it)
                                ?.id
                                ?.let(::ProjectMemoryScopeKey)
                        },
                    )
                val suffix = UUID.randomUUID().toString().replace("-", "")
                val global = "GLOBAL_$suffix"
                val markers = listOf("FIRST_$suffix", "SECOND_$suffix")
                memory.save(MemoryScope.Global, "memory_summary.md", "Shared lookup code: $global", "new")
                listOf(first, second).zip(markers).forEach { (id, marker) ->
                    memory.save(
                        MemoryScope.Project(ProjectMemoryScopeKey(id)),
                        "memory_summary.md",
                        "Project lookup code: $marker",
                        "new",
                    )
                }
                val builder = SystemPromptContext(storage, { "" }, packagedPromptTemplates, memory = memory)
                for ((index, allowed) in listOf(0 to true, 1 to true, 0 to false, 1 to false)) {
                    val session = projects.newSession(listOf(first, second)[index], null, null)
                    if (allowed) {
                        storage.sessionPermissionConfigs.setForSession(
                            session,
                            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                            2,
                        )
                    }
                    val directory =
                        FileScopePath.fromModelReference(requireNotNull(storage.sessions.resolve(session).directoryRef))
                    val prompt = builder.build(session, AgentMode.ACT, emptySet(), directory = directory)
                    assertFalse(prompt.content.contains(markers[1 - index]))
                    assertTrue(prompt.content.contains(markers[index]) == allowed)
                    assertTrue(prompt.content.contains(global) == allowed)
                    val events =
                        withTimeout(120_000) {
                            provider
                                .stream(
                                    ModelRequest(
                                        model,
                                        listOf(
                                            ModelMessage(ModelRole.SYSTEM, prompt.content),
                                            ModelMessage(
                                                ModelRole.USER,
                                                "Report the project lookup code and shared lookup code " +
                                                    "from memory data. " +
                                                    "If neither is available, reply UNKNOWN. " +
                                                    "Do not invent codes or use tools.",
                                            ),
                                        ),
                                        maxOutputTokens = 2048,
                                    ),
                                ).toList()
                        }
                    assertTrue(events.any { it is ModelEvent.Completed })
                    assertTrue(events.none { it is ModelEvent.Error })
                    val reply = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
                    println("MEMORY_SYNTHETIC_REPLY=$reply USAGE=${events.filterIsInstance<ModelEvent.Usage>()}")
                    assertFalse(reply.contains(markers[1 - index]))
                    if (allowed) {
                        assertTrue(reply.contains(markers[index]))
                        assertTrue(reply.contains(global))
                    } else {
                        assertTrue(reply.contains("UNKNOWN"))
                        assertFalse(reply.contains(markers[index]))
                        assertFalse(reply.contains(global))
                    }
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        0,
                        android.os.Bundle().apply {
                            val usage = events.filterIsInstance<ModelEvent.Usage>()
                            putString("stream", "MEMORY_RECALL project=$index allowed=$allowed usage=$usage\n")
                        },
                    )
                }
            } finally {
                storage.close()
                app.deleteDatabase("$name.db")
                root.deleteRecursively()
            }
        }
}
