package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.infrastructure.conversation.RoomConversationWorkspace
import com.helix.app.infrastructure.conversation.RoomSessionCatalog
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.app.runcontrol.PersistedRunControlStore
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.feature.conversation.application.DraftSession
import com.helix.feature.conversation.application.SessionDraft
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class SessionCatalogDeviceTest {
    @Test fun creationRenameAndArchiveKeepTheOriginalClockIdentityAndControls() =
        Fixture().use { f ->
            val id = f.catalog.create("original", null, null)
            val before = f.storage.sessions.resolve(id)
            assertEquals("catalog-1", id)
            assertEquals(1000L, before.createdAt)
            assertEquals(AgentMode.ACT, f.controls.forSession(id)?.mode)
            f.catalog.rename(id, "renamed")
            assertEquals(
                before.directoryRef,
                f.storage.sessions
                    .resolve(id)
                    .directoryRef,
            )
            f.now = 2000
            f.catalog.setArchived(id, true)
            assertEquals(
                2000L,
                f.storage.sessions
                    .resolve(id)
                    .archivedAt,
            )
            f.catalog.setArchived(id, false)
            assertNull(
                f.storage.sessions
                    .resolve(id)
                    .archivedAt,
            )
            assertEquals(
                "renamed",
                f.storage.sessions
                    .resolve(id)
                    .title,
            )
            assertEquals(
                1000L,
                f.storage.sessions
                    .resolve(id)
                    .createdAt,
            )
            assertEquals(AgentMode.ACT, f.controls.forSession(id)?.mode)
        }

    @Test fun untestedProviderAndInvalidPairRejectBeforeAllocatingOrPersisting() =
        Fixture().use { f ->
            assertThrows(IllegalArgumentException::class.java) { f.catalog.create("bad", "missing", "model") }
            assertThrows(IllegalArgumentException::class.java) { f.catalog.create("bad", null, "model") }
            assertEquals(0, f.ids)
            assertEquals(0, f.providers.networkOperations.value)
            assertEquals(emptyList<SessionEntity>(), f.storage.sessions.list())
        }

    @Test fun deletedSourceDoesNotPreventDraftSettingsAndRetainsTheBindingProblem() =
        Fixture().use { f ->
            f.storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = "draft-provider",
                    displayName = "synthetic",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = "https://synthetic.invalid/v1",
                    model = "model-a",
                    headersJson = "{}",
                    secretAlias = ProviderFactory.NO_KEY_ALIAS,
                    capabilitySnapshot =
                        ProviderCapabilities.toJsonString(
                            ProviderCapabilities(
                                streaming = true,
                                toolCalls = true,
                                parallelToolCalls = false,
                                vision = false,
                                reasoning = false,
                                jsonSchemaOutput = false,
                                maxContextTokens = 32000,
                                source = CapabilitySource.CONNECTION_ONLY,
                            ),
                        ),
                ),
            )
            val draft =
                SessionDraft(
                    DraftSession("source-present", "", "draft-provider", "model-a", 3000),
                    f.controls.defaultSnapshot(),
                )
            // An untested but existing source is retained, never silently replaced.
            f.catalog.persistDraft(draft)
            assertEquals(
                "draft-provider",
                f.storage.sessions
                    .resolve("source-present")
                    .providerId,
            )
            f.storage.providerConfigs.delete("draft-provider")
            f.catalog.persistDraft(draft.copy(session = draft.session.copy(id = "source-deleted")))
            val saved = f.storage.sessions.resolve("source-deleted")
            assertNull(saved.providerId)
            assertEquals("model-a", saved.modelId)
            assertEquals(
                com.helix.feature.conversation.presentation.ProviderBindingIssue.SOURCE_DELETED,
                f.providers.bindings.project(saved.providerId, saved.modelId),
            )
            assertEquals(draft.control, f.controls.forSession(saved.id))
            assertEquals(0, f.providers.networkOperations.value)
        }

    @Test fun invalidControlRollsBackDraftMetadataAndTheRoomWorkspaceFact() =
        Fixture().use { f ->
            val invalid =
                f.controls.defaultSnapshot().copy(budgets = TurnBudgetBounds.DEFAULT.copy(maxOutputTokens = 0))
            val draft = SessionDraft(DraftSession("draft", "draft", null, null, 3000), invalid)
            assertThrows(IllegalArgumentException::class.java) { f.catalog.persistDraft(draft) }
            assertNull(f.storage.sessions.find("draft"))
            assertNull(f.controls.forSession("draft"))
            assertEquals(
                0,
                f.storage.workspaces
                    .list()
                    .size,
            )
            // No claim that a filesystem side effect was rolled back with the Room transaction.
        }

    @Test fun projectDraftCommitsMembershipWithControlsAndRejectsStaleOrUnavailableContext() =
        Fixture().use { f ->
            val project =
                runBlocking {
                    com.helix.app.projects
                        .ProjectService(
                            f.storage,
                        ) { it }
                        .save(null, "Project", "", "", null)
                }
            val selected = requireNotNull(f.catalog.projectSelection(project))
            val row = DraftSession("project-draft", "", null, null, 3000, directoryRef = selected.directory)
            val draft = SessionDraft(row, f.controls.defaultSnapshot(), project = selected)
            f.catalog.persistDraft(draft)
            assertEquals(
                project,
                f.storage.projects
                    .forSession(row.id)
                    ?.id,
            )
            assertEquals(
                selected.directory,
                f.storage.sessions
                    .resolve(row.id)
                    .directoryRef,
            )
            val record = requireNotNull(f.storage.projects.find(project))
            f.storage.projects.archive(project, record.revision, true, 4000)
            assertThrows(IllegalStateException::class.java) {
                f.catalog.persistDraft(draft.copy(session = row.copy(id = "stale-project")))
            }
            assertNull(f.storage.sessions.find("stale-project"))
            val archived = requireNotNull(f.storage.projects.find(project))
            f.storage.projects.archive(project, archived.revision, false, 5000)
            val fresh = requireNotNull(f.catalog.projectSelection(project))
            f.storage.workspaces.markUnavailable(record.workspaceId)
            assertThrows(IllegalArgumentException::class.java) {
                f.catalog.persistDraft(draft.copy(session = row.copy(id = "lost-directory"), project = fresh))
            }
            assertNull(f.storage.sessions.find("lost-directory"))
            assertNull(f.storage.projects.forSession("lost-directory"))
        }

    @Test fun cachedUnsentProjectSelectionChangesDirectoryButExistingConversationKeepsIt() =
        Fixture().use { f ->
            val service =
                com.helix.app.projects
                    .ProjectService(f.storage) { it }
            val first = runBlocking { service.save(null, "First", "", "", null) }
            val second = runBlocking { service.save(null, "Second", "", "", null) }
            val id = f.catalog.create("unsent", null, null)
            f.catalog.selectProject(id, f.catalog.projectSelection(first))
            val directory =
                f.storage.sessions
                    .resolve(id)
                    .directoryRef
            assertEquals(f.catalog.projectSelection(first)?.directory, directory)
            val turn = f.storage.turns.start("project-busy", id, 4000)
            assertThrows(
                IllegalStateException::class.java,
            ) { f.catalog.selectProject(id, f.catalog.projectSelection(second)) }
            assertEquals(
                first,
                f.storage.projects
                    .forSession(id)
                    ?.id,
            )
            val cancelling = f.storage.turns.updateState(turn, com.helix.core.model.TurnState.CANCELLING, 0, null, null)
            f.storage.turns.updateState(cancelling, com.helix.core.model.TurnState.CANCELLED, 0, 5000, null)
            f.storage.turns.collectResult(turn.id, 5001)
            f.catalog.selectProject(id, f.catalog.projectSelection(second))
            assertEquals(
                second,
                f.storage.projects
                    .forSession(id)
                    ?.id,
            )
            assertEquals(
                directory,
                f.storage.sessions
                    .resolve(id)
                    .directoryRef,
            )
        }

    @Test fun modelDefaultsAndControlsCommitOrRollBackTogether() =
        Fixture().use { f ->
            f.storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = "provider",
                    displayName = "synthetic",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = "https://synthetic.invalid/v1",
                    model = "model-a",
                    headersJson = "{}",
                    secretAlias = ProviderFactory.NO_KEY_ALIAS,
                    capabilitySnapshot =
                        ProviderCapabilities.toJsonString(
                            ProviderCapabilities(
                                streaming = true,
                                toolCalls = true,
                                parallelToolCalls = false,
                                vision = false,
                                reasoning = false,
                                jsonSchemaOutput = false,
                                maxContextTokens = 32000,
                                source = CapabilitySource.CONNECTION_ONLY,
                            ),
                        ),
                ),
            )
            val id = f.catalog.create("draft", null, null)
            val original = requireNotNull(f.controls.forSession(id))
            val invalid = original.copy(budgets = original.budgets.copy(maxOutputTokens = 0))
            assertThrows(IllegalArgumentException::class.java) {
                f.catalog.persistSettings(id, "provider", "model-b", invalid, null)
            }
            assertNull(
                f.storage.sessions
                    .resolve(id)
                    .providerId,
            )
            assertNull(
                f.storage.sessions
                    .resolve(id)
                    .modelId,
            )
            assertEquals(original, f.controls.forSession(id))
            val next = original.copy(mode = AgentMode.CHAT)
            assertEquals(true, f.catalog.persistSettings(id, "provider", "model-b", next, null))
            assertEquals(
                "provider",
                f.storage.sessions
                    .resolve(id)
                    .providerId,
            )
            assertEquals(
                "model-b",
                f.storage.sessions
                    .resolve(id)
                    .modelId,
            )
            assertEquals(next, f.controls.forSession(id))
        }

    @Test fun missingDirectoryTargetDoesNotAllocateAnAuditIdentity() =
        Fixture().use { f ->
            assertFalse(f.catalog.setDirectory("missing", null))
            assertEquals(0, f.ids)
            assertEquals(
                0,
                f.storage.sessions
                    .list()
                    .size,
            )
        }

    @Test fun modelCreationReceiptSurvivesServiceRecreationAndRevocation(): Unit =
        Fixture().use { f ->
            val source = f.catalog.create("source", null, null)
            val caller = creationCaller(f, source)

            fun service() =
                com.helix.app.session.SessionCreationService(
                    com.helix.app.session
                        .RoomSessionCreationStore(f.storage),
                    { title -> f.catalog.create(title, null, null) },
                    { f.now },
                )
            service().setAllowed(source, true)
            val receipt = service().create(caller, "stable-key", "New")
            f.reopenStorage()
            assertEquals(receipt, service().create(caller, "stable-key", "New"))
            assertEquals(
                2,
                f.storage.sessions
                    .list()
                    .size,
            )
            assertEquals(
                emptyList<com.helix.core.storage.entity.TurnEntity>(),
                f.storage.turns.listBySession(receipt.createdSessionId),
            )
            assertNull(
                f.storage.sessions
                    .resolve(receipt.createdSessionId)
                    .providerId,
            )
            assertFalse(service().allowed(receipt.createdSessionId))
            service().setAllowed(source, false)
            assertThrows(IllegalArgumentException::class.java) {
                service().create(caller, "stable-key", "New")
            }
        }

    @Test fun receiptFailureRollsBackCreatedSessionAndDefaults() =
        Fixture().use { f ->
            val source = f.catalog.create("source", null, null)
            val caller = creationCaller(f, source)
            val base =
                com.helix.app.session
                    .RoomSessionCreationStore(f.storage)
            val failing =
                object : com.helix.app.session.SessionCreationService.Store by base {
                    override fun insert(receipt: com.helix.core.storage.entity.SessionCreationReceiptEntity): Unit =
                        error("synthetic receipt failure")
                }
            val service =
                com.helix.app.session.SessionCreationService(
                    failing,
                    { title -> f.catalog.create(title, null, null) },
                    { f.now },
                )
            service.setAllowed(source, true)
            assertThrows(IllegalStateException::class.java) { service.create(caller, "key", "New") }
            assertEquals(
                listOf(source),
                f.storage.sessions
                    .list()
                    .map { it.id },
            )
            assertNull(base.receipt(source, "key"))
        }

    private fun creationCaller(
        f: Fixture,
        source: String,
    ): com.helix.core.storage.repository.SessionHistoryCaller {
        var turn = f.storage.turns.start("creation-turn", source, f.now)
        listOf(
            com.helix.core.model.TurnState.BUILDING_CONTEXT,
            com.helix.core.model.TurnState.WAITING_MODEL,
            com.helix.core.model.TurnState.RECEIVING_MODEL,
            com.helix.core.model.TurnState.RUNNING_TOOL,
        ).forEach {
            turn = f.storage.turns.updateState(turn, it, 0, null, null)
        }
        f.storage.toolCalls.append("creation-call", turn.id, "wire-call", "session.create", "1", "{}", "RUNNING")
        return com.helix.core.storage.repository.SessionHistoryCaller(
            source,
            turn.id,
            "creation-call",
            "session.create",
        )
    }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "catalog-${UUID.randomUUID()}.db"
        private val root = File(context.filesDir, name)
        var storage = HelixStorage.open(context, name, root)
            private set

        fun reopenStorage() {
            storage.close()
            storage = HelixStorage.open(context, name, root)
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 1000L
        var ids = 0
        private val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(now)
            }
        val providers =
            ProviderService(
                storage,
                ProviderFactory(
                    CredentialLookup { ProviderFactory.NO_KEY_PLACEHOLDER },
                    ScriptedSseWire().wireClient,
                    { error("Metadata tests must not request images") },
                ),
                ProviderTestStatusStore(InMemoryLineStore()),
                idGenerator = { UUID.randomUUID().toString() },
                scope = scope,
            )
        val controls = SessionRunControlStore(storage, PersistedRunControlStore(InMemoryLineStore()))
        val catalog =
            RoomSessionCatalog(
                storage,
                providers,
                controls,
                RoomConversationWorkspace(storage) { it },
                clock,
                { "catalog-${++ids}" },
                { it },
            )

        override fun close() {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
            File(context.filesDir, "workspaces-$name").deleteRecursively()
        }
    }
}
