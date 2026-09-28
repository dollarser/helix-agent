package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.memory.MemoryService
import com.helix.core.agent.TrustLevel
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MarkdownMemoryStore
import com.helix.core.workspace.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class MemoryPromptDeviceTest {
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
            val builder = SystemPromptContext(storage, { "" }, memory = service)
            assertFalse(
                builder
                    .build(
                        "session",
                        AgentMode.ACT,
                        false,
                        true,
                        directory = path,
                    ).content
                    .contains("MEMORY_FIXTURE_CONTENT"),
            )
            storage.sessionPermissionConfigs.setForSession(
                "session",
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                2,
            )
            val enabled = builder.build("session", AgentMode.ACT, false, true, directory = path)
            assertTrue(enabled.content.contains("MEMORY_FIXTURE_CONTENT"))
            assertEquals(TrustLevel.UNTRUSTED, enabled.sections.single { it.name == "memory.context" }.trust)
            flags["enabled"] = false
            assertFalse(
                builder
                    .build(
                        "session",
                        AgentMode.ACT,
                        false,
                        true,
                        directory = path,
                    ).content
                    .contains("MEMORY_FIXTURE_CONTENT"),
            )
        } finally {
            storage.close()
            context.deleteDatabase("$id.db")
            directory.deleteRecursively()
        }
    }
}
