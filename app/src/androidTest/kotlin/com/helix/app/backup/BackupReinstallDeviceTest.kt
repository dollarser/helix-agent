package com.helix.app.backup

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.repository.ExpertDefinition
import com.helix.core.storage.repository.ExpertProfile
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.workspace.memory.MemoryScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Host preserves only this synthetic archive across an actual uninstall/reinstall of a fresh isolated package. */
class BackupReinstallDeviceTest {
    @Test fun explicitSyntheticPhase(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as HelixApplication
            require(app.packageName == PACKAGE) { "Fresh backup-only isolation required" }
            val phase = InstrumentationRegistry.getArguments().getString("backupReinstallPhase")
            require(phase in setOf("export", "queue", "verify")) { "Explicit backup phase required" }
            app.account.restore()
            check(!app.account.signedIn.value) { "No real account is admitted" }
            val c = app.awaitAppContainer()
            app.runtimeRecoveryReady.await()
            val service =
                AppBackupService(app, c.storage, c.fileManager, app.knowledge, requireNotNull(c.executionOwnership))
            when (phase) {
                "export" -> {
                    require(
                        c.storage.sessions
                            .list()
                            .isEmpty(),
                    ) { "Never export an existing profile" }
                    seed(app)
                    val selection = AppBackupSelection(workspaces = true, credentials = true)
                    service.prepareExport(selection, PASSWORD.toCharArray()).use { prepared ->
                        assertTrue(prepared.encryptedCredentials)
                        assertFalse(
                            prepared.archiveFiles
                                .getValue("state.json")
                                .readText()
                                .contains(FAKE_KEY),
                        )
                        service.export(Uri.fromFile(File(app.cacheDir, ARCHIVE)), prepared)
                    }
                    println("SYNTHETIC_REINSTALL_EXPORT_READY")
                }

                "queue" -> {
                    require(
                        c.storage.sessions
                            .list()
                            .isEmpty(),
                    ) { "Reinstall must have a fresh profile" }
                    val archive = File(app.filesDir, ARCHIVE)
                    require(archive.isFile)
                    service.prepareRestore(Uri.fromFile(archive), true, PASSWORD.toCharArray()).use { prepared ->
                        assertTrue(prepared.restoresCredentials)
                        service.queueRestore(prepared)
                        assertTrue(ProfileReplacement.pending(app))
                    }
                    println("SYNTHETIC_REINSTALL_RESTORE_QUEUED")
                }

                "verify" -> {
                    verify(app)
                }
            }
        }

    private suspend fun seed(app: HelixApplication) {
        val c = app.awaitAppContainer()
        val s = c.storage
        s.providerConfigs.save(
            ProviderConfigSpec(
                "provider",
                "Synthetic provider",
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://example.invalid/v1",
                "synthetic",
                "{}",
                "synthetic-key",
                "{}",
            ),
        )
        s.secrets.put(SecretAlias("synthetic-key"), FAKE_KEY)
        s.secrets.put(SecretAlias("helix-account-session"), "FAKE_SESSION_NEVER_RESTORE")
        listOf("session", "branch").forEach { id ->
            s.sessions.create(id, "Synthetic backup $id", "provider", "synthetic", 1)
            s.messages.append("message-$id", id, null, "USER", "TEXT", "SYNTHETIC_REINSTALL_BODY")
        }
        // Synthetic durable facts only: no model request or tool executor is dispatched.
        s.turns.start("turn", "session", 1)
        s.modelCalls.append("model", "turn", "{}", "RUNNING")
        s.toolCalls.append("running", "turn", "original-running", "write", "1", "{}", "RUNNING")
        s.toolCalls.append("pending", "turn", "original-pending", "write", "1", "{}", "PENDING")
        s.experts.save(ExpertDefinition(ExpertProfile("expert", "Synthetic expert", "Synthetic guidance")))
        requireNotNull(c.memory).save(MemoryScope.Global, "memory_summary.md", "Synthetic preference", "new")
        requireNotNull(c.skillAuthoringService).saveDraft("backup-fixture", "Synthetic draft", "Review before use.")
        val file = File(app.filesDir, "workspaces/app/output/reinstall-proof.txt")
        check(requireNotNull(file.parentFile).isDirectory)
        file.writeText("SYNTHETIC_REINSTALL_ATTACHMENT")
        val hash = DocumentStorage.hash(file.readBytes())
        s.artifacts.register(
            "attachment",
            "session",
            "scope:app:output/reinstall-proof.txt",
            "text/plain",
            file.length(),
            hash,
            file,
        )
        s.messageAttachments.bind(
            "message-session",
            listOf(MessageAttachmentRepository.Binding("attachment", "FILE", hash)),
        )
    }

    private suspend fun verify(app: HelixApplication) {
        val c = app.awaitAppContainer()
        val s = c.storage
        assertFalse(ProfileReplacement.pending(app))
        assertEquals(2, s.sessions.list().size)
        listOf("session", "branch").forEach { id ->
            assertEquals("SYNTHETIC_REINSTALL_BODY", s.messages.readContent(s.messages.resolve("message-$id")))
        }
        assertEquals(
            "Synthetic expert",
            s.experts
                .find("expert")
                ?.profile
                ?.displayName,
        )
        val memory = requireNotNull(c.memory).read(MemoryScope.Global, "memory_summary.md").markdown
        assertEquals("Synthetic preference", memory)
        val artifact = s.artifacts.resolve("attachment")
        verifyRestoredDraft(app, artifact.relativePath)
        val alias = requireNotNull(s.providerConfigs.resolve("provider").secretAlias)
        assertTrue(alias.startsWith("restored-"))
        assertEquals(FAKE_KEY, s.secrets.get(SecretAlias(alias)))
        assertFalse(s.secrets.contains(SecretAlias("helix-account-session")))
        assertEquals("INTERRUPTED", s.modelCalls.resolve("model").state)
        assertEquals("NEEDS_REVIEW", s.toolCalls.resolve("running").state)
        assertEquals("UNKNOWN", s.toolResults.byToolCall("running")?.status)
        assertEquals("CANCELLED", s.toolCalls.resolve("pending").state)
        assertEquals(
            setOf("running", "pending"),
            s.toolCalls
                .listByTurn("turn")
                .map { it.id }
                .toSet(),
        )
        val file = File(app.filesDir, "workspaces/app/${artifact.relativePath.removePrefix("scope:app:")}")
        assertEquals("SYNTHETIC_REINSTALL_ATTACHMENT", file.readText())
        assertEquals(artifact.sha256, DocumentStorage.hash(file.readBytes()))
        assertFalse(android.os.Environment.isExternalStorageManager())
        File(app.cacheDir, "reinstall-result.json").writeText(
            org.json
                .JSONObject()
                .put("restored", true)
                .put("pid", android.os.Process.myPid())
                .put("uncertainActionNotReplayed", true)
                .put("allFilesGranted", false)
                .toString(),
        )
        println("SYNTHETIC_REINSTALL_COLD_RESTORE_VERIFIED")
    }

    private fun verifyRestoredDraft(
        app: HelixApplication,
        artifactPath: String,
    ) {
        val restoredRoot =
            com.helix.core.workspace.FileScopePath
                .fromModelReference(artifactPath)
                .relativePath
                .substringBefore('/')
        assertTrue(restoredRoot.startsWith("restored-"))
        // Restore creates a new location; the old app-workspace reference must not be retargeted.
        assertFalse(File(app.filesDir, "workspaces/app/work/skills/backup-fixture/SKILL.md").exists())
        val draftDirectory = File(app.filesDir, "workspaces/app/$restoredRoot/workspace/work/skills/backup-fixture")
        val draft =
            com.helix.extensions.skills.SkillLoader().load(
                draftDirectory.toPath(),
                com.helix.extensions.skills.SkillSource.USER_IMPORTED,
            )
        assertEquals("Review before use.", draft.body.trim())
    }

    companion object {
        const val PACKAGE = "com.helix.validation.backup.developer.asrvalidation"
        private const val ARCHIVE = "synthetic-reinstall.zip"
        private const val PASSWORD = "SyntheticReinstallPass42"
        private const val FAKE_KEY = "FAKE_REINSTALL_KEY_NOT_A_REAL_CREDENTIAL"
    }
}
