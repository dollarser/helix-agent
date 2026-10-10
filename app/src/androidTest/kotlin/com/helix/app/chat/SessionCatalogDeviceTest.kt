package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    @Test fun invalidControlRollsBackDraftMetadataAndTheRoomWorkspaceFact() =
        Fixture().use { f ->
            val invalid =
                f.controls.defaultSnapshot().copy(budgets = TurnBudgetBounds.DEFAULT.copy(maxOutputTokens = 0))
            val draft = SessionDraft(SessionEntity("draft", "draft", null, null, 3000, null), invalid)
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

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "catalog-${UUID.randomUUID()}.db"
        private val root = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, root)
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
            SessionCatalog(
                storage,
                providers,
                controls,
                SessionWorkspaceRecovery(storage) { it },
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
