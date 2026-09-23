package com.helix.app.connector

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConnectorLifecycleDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val service get() = c.connectorService

    @Test
    fun authenticationBindingChangeAtSameUrlDoesNotCarryCredentials() {
        val identity = "test:${UUID.randomUUID()}"
        val base = bundle(identity, "auth")
        val oldBundle =
            base.copy(
                endpoints =
                    base.endpoints.map {
                        it.copy(needsCredential = true, authBindingHash = "a".repeat(64))
                    },
            )
        val old = service.install(oldBundle, identity)
        val alias = SecretAlias(old.endpoints.single().id)
        c.storage.secrets.put(alias, "synthetic-old-auth")
        try {
            val next =
                oldBundle.copy(
                    contentHash = "b".repeat(64),
                    endpoints = oldBundle.endpoints.map { it.copy(authBindingHash = "b".repeat(64)) },
                )
            val updated = service.install(next, identity, old.revision)
            assertNotEquals(old.endpoints.single().id, updated.endpoints.single().id)
            assertFalse(service.enabled(updated.endpoints.single()))
            assertThrows(Exception::class.java) { c.storage.secrets.get(alias) }
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
        }
    }

    @Test
    fun preparationAndCommitFailuresPreserveOldInstallationAndCredentials() {
        val identity = "test:" + UUID.randomUUID()
        val old = service.install(bundle(identity, "old"), identity)
        val alias = SecretAlias(old.endpoints.single().id)
        c.storage.secrets.put(alias, "synthetic-credential")
        try {
            listOf("preparing", "before-commit").forEach { point ->
                val faulty =
                    faultService {
                        if (it == point) {
                            assertEquals(old, service.list().single { record -> record.id == old.id })
                            assertEquals(
                                listOf(old.skills.single()),
                                c.skillRepository
                                    .list()
                                    .map { item ->
                                        item.key
                                    }.filter { key -> key.name == "fixture" },
                            )
                            throw java.nio.file.FileSystemException("fixture", null, "ENOSPC injected")
                        }
                    }
                assertThrows(IOException::class.java) {
                    faulty.install(bundle(identity, point), identity, old.revision)
                }
                assertEquals(old, service.list().single { it.id == old.id })
                assertEquals("synthetic-credential", c.storage.secrets.get(alias))
                assertEquals(
                    setOf(old.skills.single()),
                    c.skillRepository
                        .list()
                        .map { it.key }
                        .filter {
                            it.name == old.skills.single().name
                        }.toSet(),
                )
            }
            assertThrows(IllegalStateException::class.java) {
                service.install(bundle(identity, "cancel"), identity, old.revision, cancelled = { true })
            }
            assertEquals(old, service.list().single { it.id == old.id })
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
        }
    }

    @Test
    fun sqlitePublicationFailureKeepsOldManifestSelectionAndCredential() {
        val identity = "test:" + UUID.randomUUID()
        val old = service.install(bundle(identity, "old"), identity)
        val session = session()
        service.catalog.select(session, old.id, true)
        val alias = SecretAlias(old.endpoints.single().id)
        c.storage.secrets.put(alias, "synthetic-database-failure")
        val db =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                app.getDatabasePath("helix.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
        try {
            db.execSQL(
                "CREATE TRIGGER connector_fixture_failure BEFORE UPDATE ON connector_installations " +
                    "WHEN OLD.id = '${old.id}' BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END",
            )
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                service.install(bundle(identity, "new"), identity, old.revision)
            }
            assertEquals(old, service.list().single { it.id == old.id })
            assertEquals(setOf(old.id), service.catalog.selected(session))
            assertEquals("synthetic-database-failure", c.storage.secrets.get(alias))
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS connector_fixture_failure")
            db.close()
            service.list().filter { it.id == old.id }.forEach(service::remove)
            c.storage.deleteSessionPermanently(session)
        }
    }

    @Test
    fun committedReplacementIsIdempotentAndRetainsOnlyUnchangedEndpointBinding() {
        val identity = "test:" + UUID.randomUUID()
        val old = service.install(bundle(identity, "old"), identity)
        val alias = SecretAlias(old.endpoints.single().id)
        c.storage.secrets.put(alias, "synthetic-credential")
        try {
            val next = bundle(identity, "new")
            assertThrows(IOException::class.java) {
                faultService { if (it == "after-commit") throw IOException("lost reply") }
                    .install(next, identity, old.revision)
            }
            val committed = service.list().single { it.id == old.id }
            assertEquals(old.revision + 1, committed.revision)
            assertEquals(old.endpoints.single().id, committed.endpoints.single().id)
            assertEquals("synthetic-credential", c.storage.secrets.get(alias))
            assertEquals(committed, service.install(next, identity, old.revision))
            assertThrows(IllegalArgumentException::class.java) {
                service.install(bundle(identity, "stale"), identity, old.revision)
            }
            val changed = service.install(bundle(identity, "target", "/different"), identity, committed.revision)
            assertNotEquals(alias.value, changed.endpoints.single().id)
            assertThrows(Exception::class.java) { c.storage.secrets.get(alias) }
            assertFalse(service.enabled(changed.endpoints.single()))
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
        }
    }

    @Test
    fun identicalContentDoesNotClaimAnotherPackageAndReinstallDoesNotInheritSelection() {
        val identity = "test:" + UUID.randomUUID()
        val data = bundle(identity, "same")
        val first = service.install(data, identity)
        val second = service.install(data, "$identity:other")
        val session = session()
        try {
            assertNotEquals(first.id, second.id)
            service.catalog.select(session, first.id, true)
            service.remove(first)
            val fresh = service.install(data, identity)
            assertNotEquals(first.id, fresh.id)
            assertFalse(fresh.id in service.catalog.selected(session))
            assertTrue(service.sessionRows(session).any { it.id == first.id && !it.available })
        } finally {
            service.list().filter { it.identity.startsWith(identity) }.forEach(service::remove)
            c.storage.deleteSessionPermanently(session)
        }
    }

    @Test
    fun sessionDefaultsAreCopiedAndSharedSkillSourcesStayIndependent() {
        val identity = "test:" + UUID.randomUUID()
        val first = service.install(bundle(identity, "shared"), identity)
        val second = service.install(bundle(identity, "shared"), "$identity:other")
        val a = session()
        service.catalog.setDefault(first.id, true)
        val b = session()
        try {
            val key = first.skills.single()
            service.setSkillEnabled(key, true)
            assertFalse(service.catalog.skillAvailable(key, a))
            assertTrue(service.catalog.skillAvailable(key, b))
            service.catalog.setDefault(first.id, false)
            assertTrue(service.catalog.skillAvailable(key, b))
            service.catalog.select(a, second.id, true)
            assertTrue(service.catalog.skillAvailable(key, a))
            service.catalog.select(b, first.id, false)
            assertFalse(service.catalog.skillAvailable(key, b))
            service.remove(first)
            assertTrue(service.catalog.skillAvailable(key, a))
            service.catalog.claim(key, independent = true)
            service.remove(second)
            assertTrue(service.catalog.skillAvailable(key, b))
            assertTrue(
                c.skillRepository
                    .read(key, b)
                    .body
                    .contains("shared"),
            )
            c.skillRepository.removePermanentlyForPrivacy(key)
        } finally {
            service.list().filter { it.identity.startsWith(identity) }.forEach(service::remove)
            c.storage.deleteSessionPermanently(a)
            c.storage.deleteSessionPermanently(b)
        }
    }

    @Test
    fun closedSessionRejectsStaleMcpSourceWhileOtherSessionAndIndependentServerRemainAvailable() {
        val identity = "test:" + UUID.randomUUID()
        val installed = service.install(bundle(identity, "source"), identity)
        val a = session()
        val b = session()
        try {
            service.catalog.select(a, installed.id, true)
            val id = installed.endpoints.single().id
            val source = "mcp:$id:2025-03-26:" + "1".repeat(64)
            assertTrue(service.catalog.sourceAvailable(source, a))
            assertFalse(service.catalog.sourceAvailable(source, b))
            service.catalog.select(a, installed.id, false)
            assertFalse(service.catalog.sourceAvailable(source, a))
            assertTrue(service.catalog.endpointAvailable("independent-fixture", b))
            service.remove(installed)
            assertFalse(service.catalog.endpointAvailable(id, a))
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
            c.storage.deleteSessionPermanently(a)
            c.storage.deleteSessionPermanently(b)
        }
    }

    private fun faultService(boundary: (String) -> Unit) =
        ConnectorService(
            app,
            c.storage,
            c.mcpService,
            c.skillImportService,
            c.skillRepository,
            c.mcpOAuthCoordinator,
            service.catalog,
            boundary,
        )

    private fun session(): String =
        ("connector-session-" + UUID.randomUUID()).also {
            c.storage.sessions.create(it, "Connector fixture", null, null, 0)
        }

    private fun bundle(
        identity: String,
        body: String,
        path: String = "/mcp",
    ) = ConnectorPackageReader().parse(
        mapOf(
            ".codex-plugin/plugin.json" to """{"name":"fixture"}""".toByteArray(),
            ".mcp.json" to """{"mcp_servers":{"docs":{"url":"https://example.com$path"}}}""".toByteArray(),
            "skills/fixture/SKILL.md" to ("---\nname: fixture\ndescription: $identity\n---\n$body").toByteArray(),
        ),
    )
}
