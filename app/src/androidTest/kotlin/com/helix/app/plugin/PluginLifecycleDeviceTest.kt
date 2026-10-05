package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Production Room/import/selection path; no network or model invocation. */
class PluginLifecycleDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val service get() = container.pluginService

    @Test fun selectionIdentitySurvivesRepeatedEnableButNotDisableOrSessionDeletion() {
        val identity = "plugin-test:${UUID.randomUUID()}"
        val record = service.install(bundle(), identity)
        val session = UUID.randomUUID().toString()
        container.storage.sessions.create(session, "Selection fixture", null, null, 0)
        val dao = container.storage.connectors
        try {
            service.catalog.select(session, record.id, true)
            val first = requireNotNull(dao.selectionId(session, record.id))
            assertTrue(first.isNotBlank())
            service.catalog.select(session, record.id, true)
            assertEquals(first, dao.selectionId(session, record.id))
            service.catalog.select(session, record.id, false)
            assertEquals(null, dao.selectionId(session, record.id))
            service.catalog.select(session, record.id, true)
            assertNotEquals(first, dao.selectionId(session, record.id))
            container.storage.deleteSessionPermanently(session)
            assertEquals(null, dao.selectionId(session, record.id))
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
            if (container.storage.sessions.find(session) != null) container.storage.deleteSessionPermanently(session)
        }
    }

    @Test fun disabledPackageKeepsSelectionHistoryAndCredentialButHidesSkill() {
        val identity = "plugin-test:${UUID.randomUUID()}"
        val record = service.install(bundle(), identity)
        val session = UUID.randomUUID().toString()
        container.storage.sessions.create(session, "Plugin fixture", null, null, 0)
        container.storage.messages.append(UUID.randomUUID().toString(), session, null, "USER", "TEXT", "Keep history")
        val history = container.storage.messages.listBySession(session)
        val key = record.skills.single()
        val alias = SecretAlias(record.endpoints.single().id)
        container.storage.secrets.put(alias, "synthetic-plugin-credential")
        try {
            service.catalog.select(session, record.id, true)
            val selectedBefore = service.catalog.selected(session)
            service.setSkillEnabled(key, true)
            assertTrue(service.catalog.skillAvailable(key, session))
            service.setEnabled(record.id, false)
            val disabled = service.list().single { it.id == record.id }
            assertFalse(disabled.enabled)
            assertFalse(service.catalog.skillAvailable(key, session))
            assertEquals(selectedBefore, service.catalog.selected(session))
            assertEquals("synthetic-plugin-credential", container.storage.secrets.get(alias))
            assertEquals(history, container.storage.messages.listBySession(session))
            val row = service.sessionRows(session).single { it.id == record.id }
            assertEquals(0, row.readyComponents)
            assertFalse(row.ready)
            assertTrue(row.available)
            service.setEnabled(record.id, true)
            assertTrue(service.catalog.skillAvailable(key, session))
            assertFalse(service.enabled(record.endpoints.single()))
            val restored = service.sessionRows(session).single { it.id == record.id }
            assertEquals(1, restored.readyComponents)
            assertEquals(2, restored.totalComponents)
            assertFalse(restored.ready)
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
            container.storage.deleteSessionPermanently(session)
        }
    }

    @Test fun disabledUpdatePreservesChoiceAndRejectsStaleUninstall() {
        val identity = "plugin-test:${UUID.randomUUID()}"
        val old = service.install(bundle(), identity)
        try {
            service.setEnabled(old.id, false)
            val disabled = service.list().single { it.id == old.id }
            val replacement = service.install(bundle("changed"), identity, disabled.revision)
            assertEquals(old.id, replacement.id)
            assertFalse(replacement.enabled)
            assertThrows(IllegalStateException::class.java) { service.remove(old) }
            assertEquals(replacement, service.list().single { it.id == old.id })
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
        }
    }

    @Test fun addingSameUrlComponentDoesNotShareOldCredential() {
        val identity = "plugin-test:${UUID.randomUUID()}"
        val base = bundle()
        val old = service.install(base, identity)
        val alias = SecretAlias(old.endpoints.single().id)
        container.storage.secrets.put(alias, "synthetic-credential")
        try {
            val changed =
                base.copy(
                    contentHash = "f".repeat(64),
                    endpoints = base.endpoints + base.endpoints.single().copy(name = "second"),
                )
            val updated = service.install(changed, identity, old.revision)
            assertEquals(old.endpoints.single().id, updated.endpoints.first().id)
            assertNotEquals(updated.endpoints.first().id, updated.endpoints.last().id)
            assertEquals("synthetic-credential", container.storage.secrets.get(alias))
            assertThrows(Exception::class.java) {
                container.storage.secrets.get(SecretAlias(updated.endpoints.last().id))
            }
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
        }
    }

    private fun bundle(body: String = "fixture") =
        PluginPackageReader().parse(
            mapOf(
                ".claude-plugin/plugin.json" to """{"name":"plugin-fixture"}""".toByteArray(),
                ".mcp.json" to """{"mcpServers":{"docs":{"url":"https://example.com/mcp"}}}""".toByteArray(),
                "skills/lifecycle-fixture/SKILL.md" to
                    "---\nname: lifecycle-fixture\ndescription: Lifecycle fixture\n---\n$body".toByteArray(),
            ),
        )
}
