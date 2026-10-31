package com.helix.app.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.storage.repository.ExpertDefinition
import com.helix.core.storage.repository.ExpertProfile
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.workspace.memory.MemoryScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Host drives the real system picker between these opt-in phases. Only the isolated app is admitted. */
class BackupSystemJourneyDeviceTest {
    private suspend fun app(): HelixApplication {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("helix.backupSystemJourney") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
        require(app.packageName == "com.helix.agent.developer.asrvalidation")
        app.account.restore()
        check(!app.account.signedIn.value) { "Synthetic backup journey must not contain a real account" }
        app.awaitAppContainer()
        app.runtimeRecoveryReady.await()
        return app
    }

    private fun stateFile(app: HelixApplication): File {
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("journeyId"))
        require(UUID.fromString(id).toString() == id)
        return File(app.cacheDir, "backup-ui-$id.json")
    }

    @Test fun prepareSyntheticProfile(): Unit =
        runBlocking {
            val app = app()
            val c = app.awaitAppContainer()
            val state = stateFile(app)
            require(!state.exists()) { "This synthetic journey already exists" }
            val before =
                c.storage.sessions
                    .list()
                    .size
            val memory = requireNotNull(c.memory)
            val id = UUID.randomUUID().toString()
            val provider = "backup-ui-provider-$id"
            val alias = "backup-ui-key-$id"
            c.storage.providerConfigs.save(
                ProviderConfigSpec(
                    provider,
                    "Synthetic backup provider",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    "https://example.invalid/v1",
                    "synthetic",
                    "{}",
                    alias,
                    "{}",
                ),
            )
            c.storage.secrets.put(SecretAlias(alias), FAKE_KEY)
            val session = "backup-ui-source-$id"
            val branch = "backup-ui-branch-$id"
            listOf(session, branch).forEach { key ->
                c.storage.sessions.create(key, "Synthetic backup chat", provider, "synthetic", 1)
                c.storage.messages.append("message-$key", key, null, "USER", "TEXT", BODY)
            }
            c.storage.experts.save(
                ExpertDefinition(ExpertProfile("backup-ui-expert-$id", "Synthetic expert", "Synthetic guidance")),
            )
            memory.save(MemoryScope.Global, "memory_summary.md", MEMORY, "new")
            state.writeText(
                JSONObject()
                    .put("expectedSessions", before + 2)
                    .put("session", session)
                    .put("branch", branch)
                    .put("provider", provider)
                    .put("oldAlias", alias)
                    .put("sentinel", "backup-ui-post-export-$id")
                    .toString(),
            )
            println("SYNTHETIC_BACKUP_PROFILE_READY")
        }

    @Test fun preflightSystemExport(): Unit =
        runBlocking {
            val app = app()
            val source = File(app.cacheDir, "backup-ui-archive.zip")
            require(source.isFile)
            val directory = File(app.cacheDir, "backup-ui-preflight-${UUID.randomUUID()}")
            val password = "SyntheticBackupPass42".toCharArray()
            source.inputStream().use {
                val result = AppBackupRestore(app).prepare(it, directory, true, password) {}
                result.use { prepared ->
                    assertTrue(prepared.restoresCredentials)
                }
            }
        }

    @Test fun markPostExportChange(): Unit =
        runBlocking {
            val app = app()
            val facts = JSONObject(stateFile(app).readText())
            val storage = app.awaitAppContainer().storage
            val id = facts.getString("sentinel")
            require(storage.sessions.find(id) == null)
            storage.sessions.create(id, "Synthetic post-export sentinel", null, null, 2)
            assertEquals(facts.getInt("expectedSessions") + 1, storage.sessions.list().size)
            println("SYNTHETIC_POST_EXPORT_CHANGE_READY")
        }

    @Test fun verifyColdRestoredProfile(): Unit =
        runBlocking {
            val app = app()
            val c = app.awaitAppContainer()
            val facts = JSONObject(stateFile(app).readText())
            assertFalse(ProfileReplacement.pending(app))
            assertEquals(
                facts.getInt("expectedSessions"),
                c.storage.sessions
                    .list()
                    .size,
            )
            assertEquals(null, c.storage.sessions.find(facts.getString("sentinel")))
            listOf("session", "branch").forEach { field ->
                val id = facts.getString(field)
                assertEquals(BODY, c.storage.messages.readContent(c.storage.messages.resolve("message-$id")))
            }
            assertTrue(
                c.storage.experts
                    .list()
                    .any { it.profile.displayName == "Synthetic expert" },
            )
            assertEquals(MEMORY, requireNotNull(c.memory).read(MemoryScope.Global, "memory_summary.md").markdown)
            val alias =
                requireNotNull(
                    c.storage.providerConfigs
                        .resolve(facts.getString("provider"))
                        .secretAlias,
                )
            assertTrue(alias.startsWith("restored-"))
            assertEquals(FAKE_KEY, c.storage.secrets.get(SecretAlias(alias)))
            assertFalse(c.storage.secrets.contains(SecretAlias(facts.getString("oldAlias"))))
            assertFalse(c.storage.secrets.contains(SecretAlias("helix-account-session")))
            val schedules =
                com.helix.core.storage.scheduled
                    .ScheduledAgentStore(app)
            try {
                assertTrue(schedules.dao.rules().all { !it.enabled && it.nextAt == null })
                assertTrue(schedules.dao.unfinished().isEmpty())
            } finally {
                schedules.close()
            }
            File(app.cacheDir, "backup-system-journey-result.json").writeText(
                org.json
                    .JSONObject()
                    .put("coldRestore", true)
                    .put("chatsAndBranch", true)
                    .put("memoryAndExpert", true)
                    .put("encryptedSyntheticKey", true)
                    .put("sentinelRemoved", true)
                    .toString(),
            )
            println("SYNTHETIC_COLD_RESTORE_VERIFIED")
        }

    companion object {
        private const val FAKE_KEY = "FAKE_BACKUP_UI_KEY_NOT_A_REAL_CREDENTIAL"
        private const val BODY = "SYNTHETIC_BACKUP_UI_BODY"
        private const val MEMORY = "Synthetic backup preference."
    }
}
