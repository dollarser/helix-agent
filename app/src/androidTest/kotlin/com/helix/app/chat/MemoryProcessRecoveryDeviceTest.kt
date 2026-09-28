package com.helix.app.chat

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.memory.createMemoryService
import com.helix.core.agent.TrustLevel
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.memory.MemoryScope
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
            val memory = createMemoryService(context)
            memory.configure("enabled", true)
            memory.save(MemoryScope.Global, "memory_summary.md", FIXTURE, "new")
            File(context.noBackupFilesDir, PID_FILE).writeText(Process.myPid().toString())
            // Deliberately leave Room/files/settings open: the next instrumentation must prove
            // their durable state after abrupt process death rather than a graceful close/reopen.
            Process.killProcess(Process.myPid())
            error("Expected process death")
        }
        try {
            val previousPid = File(context.noBackupFilesDir, PID_FILE).readText().toInt()
            assertNotEquals(previousPid, Process.myPid())
            val memory = createMemoryService(context)
            assertTrue(memory.enabled)
            assertTrue(memory.read(MemoryScope.Global, "memory_summary.md").markdown.contains(FIXTURE))
            val directory =
                FileScopePath.fromModelReference(
                    requireNotNull(storage.sessions.resolve(SESSION).directoryRef),
                )
            val prompt =
                SystemPromptContext(storage, { "" }, memory = memory)
                    .build(SESSION, AgentMode.ACT, false, true, directory = directory)
            assertTrue(prompt.content.contains(FIXTURE))
            assertEquals(TrustLevel.UNTRUSTED, prompt.sections.single { it.name == "memory.context" }.trust)
        } finally {
            storage.close()
            context.deleteDatabase(DATABASE)
            content.parentFile?.deleteRecursively()
            File(context.filesDir, "memory").deleteRecursively()
            context.deleteSharedPreferences("memory-settings")
            File(context.noBackupFilesDir, PID_FILE).delete()
        }
    }

    private companion object {
        const val DATABASE = "p1-memory-process.db"
        const val SESSION = "memory-process-session"
        const val PID_FILE = "recovery-device-pid"
        const val FIXTURE = "MEMORY_PROCESS_RECOVERY_FIXTURE"
    }
}
