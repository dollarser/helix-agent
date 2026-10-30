package com.helix.app.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.app.files.FileSourceKind
import com.helix.app.knowledge.KnowledgeService
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.backup.AppBackupArchive
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.ConversationReferenceManifest
import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.ExpertDefinition
import com.helix.core.storage.repository.ExpertProfile
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Staging only, under a private synthetic Context. Never queues replacement of the daily profile. */
class AppBackupRestoreDeviceTest {
    @Suppress("LongMethod") // Isolated end-to-end fixture: source, staging, restart and durable effects.
    @Test
    fun completeLogicalProfileRebuildPreservesHistoryAndParksUncertainExecution(): Unit =
        runBlocking {
            withContext(Dispatchers.IO) {
                val base = InstrumentationRegistry.getInstrumentation().targetContext
                require(base.packageName.endsWith(".asrvalidation")) { "Isolated validation package required" }
                val root = File(base.cacheDir, "profile-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val sourceContext = FixtureContext(base, File(root, "source"))
                val targetContext = FixtureContext(base, File(root, "target"))
                val source = HelixStorage.create(sourceContext)
                val documents = FixtureDocuments()
                val notes = KnowledgeStore(documents, FixtureDocuments())
                val knowledge = KnowledgeService(sourceContext, store = notes)
                val oldConsumerFlag = ProfileReplacement.consumersStarted
                val oldAttemptFlag = ProfileReplacement.restoreAttempted
                try {
                    source.providerConfigs.save(
                        ProviderConfigSpec(
                            "provider",
                            "Synthetic provider",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            "https://example.invalid/v1",
                            "synthetic-chat",
                            "{}",
                            "synthetic-key",
                            "{}",
                        ),
                    )
                    source.secrets.put(SecretAlias("synthetic-key"), "FAKE_KEY_NOT_A_REAL_CREDENTIAL")
                    source.secrets.put(SecretAlias("helix-account-session"), "FAKE_ACCOUNT_SESSION_NOT_A_LOGIN")
                    source.sessions.create("session", "Synthetic chat", "provider", "synthetic-chat", 1)
                    source.turns.start("turn", "session", 1)
                    source.modelCalls.append("model-call", "turn", "{}", "RUNNING")
                    source.messages.append("message", "session", "turn", "USER", "TEXT", "SYNTHETIC_BACKUP_BODY")
                    source.toolCalls.append("running", "turn", "external-running", "write", "1", "{}", "RUNNING")
                    source.toolCalls.append("pending", "turn", "external-pending", "write", "1", "{}", "PENDING")
                    source.experts.save(
                        ExpertDefinition(ExpertProfile("expert", "Synthetic expert", "Use selected sources")),
                    )
                    notes.saveNote(KnowledgeNote("note", "Synthetic note", "# Preserved note", 1, 1))
                    val memory = File(sourceContext.filesDir, "memory/global/MEMORY.md")
                    check(requireNotNull(memory.parentFile).mkdirs())
                    memory.writeText("Synthetic preference")
                    val roots =
                        ScopeRootResolver { scope ->
                            if (scope == "app") {
                                File(sourceContext.filesDir, "workspaces/app").toPath()
                            } else {
                                source.workspaces.managedDirectory(scope)
                            }
                        }
                    val workspace = WorkspaceArtifactStore(roots).also { it.ensureLayout("app") }
                    addReferenceFixtures(source, roots)
                    addUnavailableRule(sourceContext)
                    val manager =
                        FileManagerService(workspace, roots, "app", workspaceSources = {
                            source.workspaces.list().map {
                                FileSource(
                                    it.id,
                                    "Synthetic workspace",
                                    FileSourceKind.WORKSPACE,
                                    true,
                                )
                            }
                        })
                    val stage = File(root, "capture").apply { check(mkdirs()) }
                    val captured =
                        AppBackupCapture(sourceContext, source, manager, knowledge)
                            .prepare(
                                stage,
                                AppBackupSelection(library = true, credentials = true),
                                "synthetic-passphrase".toCharArray(),
                            ) {}
                    captured.use {
                        val archive = File(root, "profile.zip")
                        archive.outputStream().use { AppBackupArchive().write(captured.archiveFiles, it) {} }
                        assertTrue(captured.archiveFiles.containsKey("credentials.enc"))
                        assertFalse(
                            captured.archiveFiles
                                .getValue("state.json")
                                .readText()
                                .contains("FAKE_KEY"),
                        )
                        val publicTarget = FixtureDocuments()
                        val coordinator = ProfileRestoreCoordinator(targetContext) { publicTarget }
                        val restoreStage = coordinator.stageDirectory()
                        val prepared =
                            archive.inputStream().use {
                                AppBackupRestore(targetContext).prepare(
                                    it,
                                    restoreStage,
                                    true,
                                    "synthetic-passphrase".toCharArray(),
                                ) {}
                            }
                        prepared.use {
                            assertEquals(
                                "Synthetic preference",
                                File(requireNotNull(prepared.roots["files/memory"]), "global/MEMORY.md").readText(),
                            )
                            assertTrue(prepared.library.isNotEmpty())
                            assertTrue(prepared.restoresCredentials)
                            val restored =
                                HelixStorage.open(
                                    targetContext,
                                    requireNotNull(prepared.roots["databases/helix.db"]).absolutePath,
                                    requireNotNull(prepared.roots["files/helix-content"]),
                                )
                            try {
                                assertEquals("Synthetic chat", restored.sessions.resolve("session").title)
                                assertEquals(
                                    "SYNTHETIC_BACKUP_BODY",
                                    restored.messages.readContent(restored.messages.resolve("message")),
                                )
                                assertEquals(
                                    "Synthetic expert",
                                    restored.experts
                                        .find("expert")
                                        ?.profile
                                        ?.displayName,
                                )
                                assertEquals("NEEDS_REVIEW", restored.turns.resolve("turn").state)
                                assertEquals("NEEDS_REVIEW", restored.toolCalls.resolve("running").state)
                                assertEquals("CANCELLED", restored.toolCalls.resolve("pending").state)
                                assertEquals("UNKNOWN", restored.toolResults.byToolCall("running")?.status)
                                assertEquals("CANCELLED", restored.toolResults.byToolCall("pending")?.status)
                            } finally {
                                restored.close()
                            }
                            val previous = HelixStorage.create(targetContext)
                            previous.sessions.create("old", "Previous profile", null, null, 1)
                            previous.secrets.put(SecretAlias("helix-account-session"), "OLD_FAKE_ACCOUNT_NOT_A_LOGIN")
                            val ownership =
                                ExecutionOwnership(
                                    object : ExecutionOwnership.Store {
                                        override fun owners() = emptySet<ExecutionOwnership.Owner>()

                                        override fun update(
                                            expected: Set<ExecutionOwnership.Owner>,
                                            replacement: Set<ExecutionOwnership.Owner>,
                                        ) = false
                                    },
                                )
                            try {
                                coordinator.queue(prepared, previous, ownership)
                            } finally {
                                previous.close()
                            }
                            ProfileReplacement.consumersStarted = false
                            coordinator.recoverBeforeConsumers()
                            val actual = HelixStorage.create(targetContext)
                            try {
                                assertEquals("Synthetic chat", actual.sessions.resolve("session").title)
                                val call = actual.modelCalls.resolve("model-call")
                                assertEquals("INTERRUPTED", call.state)
                                assertTrue(requireNotNull(call.settledAt) >= call.createdAt)
                                assertReferenceFixtures(actual, targetContext)
                                assertUnavailableRule(targetContext)
                                val alias = requireNotNull(actual.providerConfigs.resolve("provider").secretAlias)
                                assertEquals("FAKE_KEY_NOT_A_REAL_CREDENTIAL", actual.secrets.get(SecretAlias(alias)))
                                assertFalse(actual.secrets.contains(SecretAlias("helix-account-session")))
                                assertEquals(null, actual.sessions.find("old"))
                                val binding = requireNotNull(actual.workspaces.binding("session"))
                                assertTrue(
                                    actual.workspaces
                                        .managedDirectory(binding.workspaceId)
                                        .toFile()
                                        .isDirectory,
                                )
                                assertEquals(1, publicTarget.list("notes").size)
                            } finally {
                                actual.close()
                            }
                            coordinator.completeAfterConsumers()
                            assertFalse(ProfileReplacement.pending(targetContext))
                        }
                    }
                } finally {
                    knowledge.close()
                    source.close()
                    sourceContext.cleanPreferences()
                    targetContext.cleanPreferences()
                    ProfileReplacement.consumersStarted = oldConsumerFlag
                    ProfileReplacement.restoreAttempted = oldAttemptFlag
                    safeDeleteBackupTree(root)
                }
            }
        }

    private suspend fun addUnavailableRule(context: Context) {
        val store =
            com.helix.core.storage.scheduled
                .ScheduledAgentStore(context)
        try {
            store.dao.save(
                com.helix.core.storage.scheduled.ScheduledAgentRule(
                    "orphan-rule",
                    "Synthetic old rule",
                    "Synthetic prompt",
                    "{\"maxRuntimeMillis\":1000}",
                    "old-scope",
                    "deleted-session",
                    "12:00",
                    "UTC",
                    "ONCE",
                    enabled = true,
                ),
            )
        } finally {
            store.close()
        }
    }

    private suspend fun assertUnavailableRule(context: Context) {
        val store =
            com.helix.core.storage.scheduled
                .ScheduledAgentStore(context)
        try {
            val rule = requireNotNull(store.dao.rule("orphan-rule"))
            assertFalse(rule.enabled)
            assertEquals("SCOPE_CHANGED", rule.status)
            assertEquals("deleted-session", rule.sessionId)
            assertEquals("", rule.scopeHash)
        } finally {
            store.close()
        }
    }

    private fun addReferenceFixtures(
        source: HelixStorage,
        roots: ScopeRootResolver,
    ) {
        source.sessions.create("source-session", "Synthetic source", "provider", "synthetic-chat", 1)
        source.turns.start("source-turn", "source-session", 1)
        source.messages.append("source-message", "source-session", "source-turn", "USER", "TEXT", "Reference content")
        val manifest = ConversationReferenceManifest(1, "a".repeat(64), 1, 17).encode()
        source.messageReferenceSnapshots.bind(
            "message",
            listOf(
                ConversationReferenceSnapshotInput(
                    "source-session",
                    "Synthetic source",
                    ConversationReferenceKind.HISTORY,
                    listOf("source-message"),
                    manifest,
                ),
            ),
            1,
        )
        val file =
            File(roots.resolveRoot("app").toFile(), "synthetic-attachment.txt").apply {
                writeText("SYNTHETIC_ATTACHMENT")
            }
        val hash = DocumentStorage.hash(file.readBytes())
        source.artifacts.register(
            "attachment",
            "session",
            "scope:app:synthetic-attachment.txt",
            "text/plain",
            file.length(),
            hash,
            file,
        )
        source.messageAttachments.bind(
            "message",
            listOf(MessageAttachmentRepository.Binding("attachment", "FILE", hash)),
        )
    }

    private fun assertReferenceFixtures(
        actual: HelixStorage,
        context: Context,
    ) {
        val reference = actual.messageReferenceSnapshots.forMessage("message").single()
        assertEquals("source-session", reference.sourceSessionId)
        assertEquals(listOf("source-message"), reference.sourceMessageIds)
        assertEquals("Reference content", actual.messages.readContent(actual.messages.resolve("source-message")))
        val attachment = actual.messageAttachments.listByMessage("message").single()
        val artifact = actual.artifacts.resolve(attachment.artifactId)
        assertEquals(attachment.boundSha256, artifact.sha256)
        val file = File(context.filesDir, "workspaces/app/${artifact.relativePath.removePrefix("scope:app:")}")
        assertEquals("SYNTHETIC_ATTACHMENT", file.readText())
        assertEquals(artifact.sha256, DocumentStorage.hash(file.readBytes()))
    }
}

private class FixtureContext(
    base: Context,
    private val root: File,
) : ContextWrapper(base) {
    private val prefix = "backup-fixture-${UUID.randomUUID()}-"
    private val preferences = mutableSetOf<String>()

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = File(root, "files").apply { check(isDirectory || mkdirs()) }

    override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { check(isDirectory || mkdirs()) }

    override fun getCacheDir(): File = File(root, "cache").apply { check(isDirectory || mkdirs()) }

    override fun getDatabasePath(name: String): File =
        (if (File(name).isAbsolute) File(name) else File(root, "databases/$name")).also {
            val parent = requireNotNull(it.parentFile)
            check(parent.isDirectory || parent.mkdirs())
        }

    override fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences {
        preferences += name
        return baseContext.getSharedPreferences(prefix + name, mode)
    }

    fun cleanPreferences() {
        preferences.forEach { check(baseContext.deleteSharedPreferences(prefix + it)) }
    }
}

private class FixtureDocuments : DocumentStorage {
    private val files = linkedMapOf<String, ByteArray>()

    override fun list(directory: String) = references(directory).map { read(it, 16 * 1024 * 1024) }

    override fun references(directory: String) = files.keys.filter { it.substringBeforeLast('/') == directory }

    override fun read(
        reference: String,
        maxBytes: Int,
    ): DocumentStorage.File {
        val bytes = files.getValue(reference)
        require(bytes.size <= maxBytes)
        return DocumentStorage.File(
            reference,
            reference.substringAfterLast('/'),
            bytes.copyOf(),
            DocumentStorage.hash(bytes),
        )
    }

    override fun save(
        directory: String,
        name: String,
        bytes: ByteArray,
        previous: DocumentStorage.File?,
        mimeType: String,
    ): DocumentStorage.File {
        require(previous == null)
        files["$directory/$name"] = bytes.copyOf()
        return read("$directory/$name", bytes.size)
    }

    override fun delete(previous: DocumentStorage.File) {
        files.remove(previous.reference)
    }
}
