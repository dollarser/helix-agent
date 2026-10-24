package com.helix.app.chat

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.application.context.SystemPromptContext
import com.helix.app.infrastructure.prompt.packagedPromptTemplates
import com.helix.app.memory.MemoryService
import com.helix.app.memory.createMemoryService
import com.helix.app.projects.ProjectService
import com.helix.core.agent.TrustLevel
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MemoryScope
import com.helix.core.workspace.memory.ProjectMemoryScopeKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Two instrumentations separated by actual app-process death; app data is intentionally retained. */
class MemoryProcessRecoveryDeviceTest {
    @Test fun markdownSettingAndPromptTrustSurviveProcessDeath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val content = File(context.cacheDir, "p1-memory-process/content")
        val storage = HelixStorage.open(context, DATABASE, content)
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        if (phase?.startsWith("setup") == true) {
            File(context.filesDir, "memory").deleteRecursively()
            context.deleteSharedPreferences("memory-settings")
            File(context.noBackupFilesDir, PID_FILE).delete()
            storage.sessions.create(SESSION, "Memory recovery", null, null, 1)
            storage.sessionPermissionConfigs.setForSession(
                SESSION,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                2,
            )
            val memory = memory(context, storage)
            memory.configure("enabled", true)
            memory.save(MemoryScope.Global, "memory_summary.md", FIXTURE, "new")
            prepareProjectMemory(context, storage, memory)
            File(context.noBackupFilesDir, PID_FILE).writeText(Process.myPid().toString())
            // Deliberately leave Room/files/settings open: the next instrumentation must prove
            // their durable state after abrupt process death rather than a graceful close/reopen.
            Process.killProcess(Process.myPid())
            error("Expected process death")
        }
        try {
            val previousPid = File(context.noBackupFilesDir, PID_FILE).readText().toInt()
            assertNotEquals(previousPid, Process.myPid())
            val memory = memory(context, storage)
            assertTrue(memory.enabled)
            assertTrue(memory.read(MemoryScope.Global, "memory_summary.md").markdown.contains(FIXTURE))
            assertProjectMemory(context, storage, memory)
            val directory =
                FileScopePath.fromModelReference(
                    requireNotNull(storage.sessions.resolve(SESSION).directoryRef),
                )
            val prompt =
                SystemPromptContext(storage, { "" }, packagedPromptTemplates, memory = memory)
                    .build(SESSION, AgentMode.ACT, setOf("memory.read"), directory = directory)
            assertTrue(prompt.content.contains(FIXTURE))
            assertTrue(prompt.content.contains(PROJECT_FIXTURE))
            assertTrue(prompt.content.contains("PROJECT_RECOVERY_INSTRUCTIONS"))
            assertEquals(2, prompt.sections.count { it.name.startsWith("memory.summary.") })
            assertTrue(
                prompt.sections.filter { it.name.startsWith("memory.summary.") }.all {
                    it.trust ==
                        TrustLevel.UNTRUSTED
                },
            )
        } finally {
            storage.close()
            context.deleteDatabase(DATABASE)
            content.parentFile?.deleteRecursively()
            File(context.filesDir, "memory").deleteRecursively()
            context.deleteSharedPreferences("memory-settings")
            File(context.noBackupFilesDir, PID_FILE).delete()
            File(context.noBackupFilesDir, PROJECT_FILE).delete()
        }
    }

    private fun memory(
        context: Context,
        storage: HelixStorage,
    ) = createMemoryService(
        context,
        project = {
            storage.projects
                .forSession(it)
                ?.id
                ?.let(::ProjectMemoryScopeKey)
        },
    )

    private fun prepareProjectMemory(
        context: Context,
        storage: HelixStorage,
        memory: MemoryService,
    ) {
        val project =
            runBlocking {
                val projects = ProjectService(storage) { it }
                val id = projects.save(null, "Memory recovery project", "", "PROJECT_RECOVERY_INSTRUCTIONS", null)
                projects.assign(SESSION, id)
                id
            }
        memory.save(MemoryScope.Project(ProjectMemoryScopeKey(project)), "memory_summary.md", PROJECT_FIXTURE, "new")
        File(context.noBackupFilesDir, PROJECT_FILE).writeText(project)
    }

    private fun assertProjectMemory(
        context: Context,
        storage: HelixStorage,
        memory: MemoryService,
    ) {
        val project = File(context.noBackupFilesDir, PROJECT_FILE).readText()
        assertEquals(project, storage.projects.forSession(SESSION)?.id)
        val scope = MemoryScope.Project(ProjectMemoryScopeKey(project))
        assertTrue(memory.read(scope, "memory_summary.md").markdown.contains(PROJECT_FIXTURE))
    }

    private companion object {
        const val DATABASE = "p1-memory-process.db"
        const val SESSION = "memory-process-session"
        const val PID_FILE = "recovery-device-pid"
        const val PROJECT_FILE = "recovery-memory-project-id"
        const val FIXTURE = "MEMORY_PROCESS_RECOVERY_FIXTURE"
        const val PROJECT_FIXTURE = "PROJECT_MEMORY_PROCESS_RECOVERY_FIXTURE"
    }
}
