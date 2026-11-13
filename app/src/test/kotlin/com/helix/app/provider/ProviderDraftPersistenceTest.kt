package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.core.storage.dao.ProviderConfigDao
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.repository.ProviderConfigRepository
import com.helix.provider.catalog.ProviderTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDraftPersistenceTest {
    @Test fun savesWholeDraftAndRetriesWithoutDuplicateCreation() {
        val fixture = Fixture()
        val session = fixture.session()
        fixture.save(session)
        fixture.save(session)
        assertEquals(1, fixture.dao.rows.size)
        assertEquals(fixture.selection, fixture.status.selectedModels.selection(session.providerId))
        assertEquals(
            "fixture-key",
            fixture.secrets.get(
                SecretAlias(
                    fixture.dao.rows
                        .getValue(session.providerId)
                        .secretAlias!!,
                ),
            ),
        )
        assertThrows(ProviderDraftSaveConflictException::class.java) {
            fixture.persistence.save(
                session,
                fixture.draft.copy(displayName = "Changed after save"),
                null,
                fixture.selection,
            )
        }
    }

    @Test fun failedInsertRestoresSelectionsAndSecretsThenCanRetry() {
        val fixture = Fixture()
        val session = fixture.session()
        fixture.dao.failInsert = true
        assertThrows(IllegalStateException::class.java) { fixture.save(session) }
        assertTrue(fixture.dao.rows.isEmpty())
        assertTrue(fixture.secrets.aliases().isEmpty())
        assertEquals(ProviderModelSelection(), fixture.status.selectedModels.selection(session.providerId))
        fixture.dao.failInsert = false
        fixture.save(session)
        assertEquals(setOf("new-provider"), fixture.dao.rows.keys)
    }

    @Test fun blankKeyRetainsAliasAndReplacementRotatesAfterSaving() {
        val fixture = Fixture()
        fixture.save(fixture.session())
        val original = fixture.dao.rows.getValue("new-provider")
        val session = fixture.editSession()
        fixture.persistence.save(session, fixture.draft.copy(displayName = "Rename"), "", fixture.selection)
        assertEquals(
            original.secretAlias,
            fixture.dao.rows
                .getValue("new-provider")
                .secretAlias,
        )
        fixture.persistence.save(fixture.editSession(), fixture.draft, "replacement-key", fixture.selection)
        val replacement = fixture.dao.rows.getValue("new-provider")
        assertNotEquals(original.secretAlias, replacement.secretAlias)
        assertFalse(fixture.secrets.contains(SecretAlias(original.secretAlias!!)))
        assertEquals("replacement-key", fixture.secrets.get(SecretAlias(replacement.secretAlias!!)))
    }

    @Test fun failedEditKeepsOldCredentialAndRow() {
        val fixture = Fixture()
        fixture.save(fixture.session())
        val original = fixture.dao.rows.getValue("new-provider")
        fixture.dao.failNextUpdate = true
        assertThrows(IllegalStateException::class.java) {
            fixture.persistence.save(
                fixture.editSession(),
                fixture.draft.copy(displayName = "New"),
                "replacement",
                fixture.selection,
            )
        }
        assertEquals(original, fixture.dao.rows.getValue("new-provider"))
        assertEquals(setOf(SecretAlias(original.secretAlias!!)), fixture.secrets.aliases())
    }

    @Test fun concurrentNameOrSelectionChangesConflictBeforeAnyWrite() {
        val fixture = Fixture()
        fixture.save(fixture.session())
        val session = fixture.editSession()
        fixture.dao.rows[session.providerId] =
            fixture.dao.rows
                .getValue(session.providerId)
                .copy(displayName = "External")
        assertThrows(ProviderDraftSaveConflictException::class.java) { fixture.save(session) }
        assertEquals(
            "External",
            fixture.dao.rows
                .getValue(session.providerId)
                .displayName,
        )
        val next = fixture.editSession()
        fixture.status.selectedModels.write(next.providerId, listOf("external-model"))
        assertThrows(ProviderDraftSaveConflictException::class.java) { fixture.save(next) }
    }

    @Test fun capabilityRefreshDoesNotConflictOrLoseNewEvidence() {
        val fixture = Fixture()
        fixture.save(fixture.session())
        val session = fixture.editSession()
        val updated =
            fixture.dao.rows
                .getValue(session.providerId)
                .copy(capabilitySnapshot = "{\"latest\":true}")
        fixture.dao.rows[session.providerId] = updated
        fixture.persistence.save(session, fixture.draft.copy(displayName = "Rename"), null, fixture.selection)
        assertEquals(
            updated.capabilitySnapshot,
            fixture.dao.rows
                .getValue(session.providerId)
                .capabilitySnapshot,
        )
    }

    private class Fixture {
        val dao = FakeDao()
        val secrets = FakeSecrets()
        val status = ProviderTestStatusStore(InMemoryLineStore())
        private var ids = 0
        val persistence =
            ProviderDraftPersistence(
                ProviderConfigRepository(dao),
                secrets,
                status,
                DebugCustomApiStore(InMemoryLineStore()),
                { "key-${++ids}" },
            )
        val draft =
            (
                ProviderComposer.compose(
                    ProviderTemplateCatalog.sglang,
                    "Example",
                    "https://example.test/v1",
                    "first",
                    emptyMap(),
                ) as ComposeOutcome.Ok
            ).draft
        val selection = ProviderModelSelection(listOf("first", "second"), true, listOf("second"))

        fun session() = ProviderDraftSaveSession("new-provider", null, ProviderModelSelection())

        fun editSession() =
            ProviderDraftSaveSession(
                "new-provider",
                configFrom(dao.rows.getValue("new-provider")),
                status.selectedModels.selection("new-provider"),
            )

        fun save(session: ProviderDraftSaveSession) = persistence.save(session, draft, "fixture-key", selection)
    }

    private class FakeSecrets : SecretStore {
        private val values = mutableMapOf<SecretAlias, String>()

        override fun put(
            alias: SecretAlias,
            secret: String,
        ) {
            values[alias] = secret
        }

        override fun get(alias: SecretAlias) = values.getValue(alias)

        override fun delete(alias: SecretAlias) {
            values.remove(alias)
        }

        override fun contains(alias: SecretAlias) = alias in values

        override fun aliases() = values.keys.toSet()
    }

    private class FakeDao : ProviderConfigDao {
        val rows = mutableMapOf<String, ProviderConfigEntity>()
        var failInsert = false
        var failNextUpdate = false

        override fun insert(config: ProviderConfigEntity) {
            check(!failInsert)
            check(config.id !in rows)
            rows[config.id] = config
        }

        override fun update(
            id: String,
            displayName: String,
            protocol: String?,
            endpoint: String?,
            model: String,
            headersJson: String,
            secretAlias: String?,
            capabilitySnapshot: String,
            provisioningKind: String,
            transportKind: String,
            authKind: String,
        ): Int {
            if (failNextUpdate) {
                failNextUpdate = false
                error("Update failed")
            }
            if (id !in rows) return 0
            rows[id] =
                ProviderConfigEntity(
                    id,
                    displayName,
                    protocol,
                    endpoint,
                    model,
                    headersJson,
                    secretAlias,
                    capabilitySnapshot,
                    provisioningKind,
                    transportKind,
                    authKind,
                )
            return 1
        }

        override fun delete(id: String) = if (rows.remove(id) == null) 0 else 1

        override fun byId(id: String) = rows[id]

        override fun list() = rows.values.toList()
    }
}
