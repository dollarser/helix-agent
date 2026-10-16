package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Synthetic production install/selection/read; never operates a user account or device app. */
class PluginSkillContributionDeviceTest {
    @Test fun nativeBrowserEntryAndReferenceShareExactIdentityAndSelection() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val session = UUID.randomUUID().toString()
        c.storage.sessions.create(session, "Native reference fixture", null, null, 0)
        val plugin = c.pluginService.list().single { it.native?.pluginId == "browser" }
        try {
            c.pluginService.selectFromUser(session, plugin.id, true)
            val key =
                c.skillRepository
                    .list(session)
                    .single { it.key.name == "browser" }
                    .key
            val entry = c.skillRepository.read(key, session)
            assertTrue(entry.body.toByteArray().size < 1024)
            assertTrue(entry.body.contains("references/browser-workflow.md"))
            val reference = c.skillRepository.readResource(key, "references/browser-workflow.md", session)
            assertTrue(reference.content.contains("Do not replay"))
            assertEquals("utf-8", reference.encoding)
            assertEquals(
                reference.content
                    .toByteArray()
                    .size
                    .toLong(),
                reference.sizeBytes,
            )
            assertThrows(IllegalArgumentException::class.java) {
                c.skillRepository.readResource(
                    key.copy(snapshotHash = "0".repeat(64)),
                    "references/browser-workflow.md",
                    session,
                )
            }
            c.skillRepository.setEnabled(key, false, com.helix.extensions.skills.SkillEnablementScope.SESSION, session)
            assertThrows(IllegalArgumentException::class.java) {
                c.skillRepository.readResource(key, "references/browser-workflow.md", session)
            }
            c.skillRepository.setEnabled(key, true, com.helix.extensions.skills.SkillEnablementScope.SESSION, session)
            c.pluginService.selectFromUser(session, plugin.id, false)
            assertThrows(IllegalArgumentException::class.java) {
                c.skillRepository.readResource(key, "references/browser-workflow.md", session)
            }
        } finally {
            c.storage.deleteSessionPermanently(session)
        }
    }

    @Test fun passiveReadinessKeepsWorkingSkillSeparateFromUnconnectedMcp() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val name = "partial-${UUID.randomUUID()}"
        val bundle =
            PluginPackageReader().parse(
                mapOf(
                    ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                    ".mcp.json" to """{"mcpServers":{"test":{"url":"https://example.invalid/mcp"}}}""".toByteArray(),
                    "skills/$name/SKILL.md" to
                        "---\nname: $name\ndescription: Partial workflow\n---\nDraft only".toByteArray(),
                ),
            )
        val installed = c.pluginService.install(bundle, "fixture:$name")
        val session = UUID.randomUUID().toString()
        c.storage.sessions.create(session, "Partial readiness fixture", null, null, 0)
        try {
            c.pluginService.setSkillEnabled(installed.skills.single(), true)
            c.pluginService.selectFromUser(session, installed.id, true)
            val initial = c.pluginService.sessionRows(session).single { it.id == installed.id }
            assertFalse(
                requireNotNull(initial.readiness)
                    .components
                    .single {
                        it.kind == com.helix.extensions.plugin.PluginComponentKind.MCP
                    }.saved,
            )
            // Explicit configuration action; installation alone does not create a live server row.
            val endpoint = installed.endpoints.single()
            c.mcpService.registerDisabled(endpoint.id, endpoint.endpoint.url, null)
            repeat(3) {
                val state = c.pluginService.sessionRows(session).single { it.id == installed.id }
                assertTrue(state.selected)
                assertEquals(1, state.readyComponents)
                assertEquals(2, state.totalComponents)
                assertFalse(state.ready)
                assertEquals(null, state.setupReason)
                val mcp =
                    requireNotNull(state.readiness).components.single {
                        it.kind == com.helix.extensions.plugin.PluginComponentKind.MCP
                    }
                assertTrue(mcp.saved)
                assertFalse(mcp.loaded)
                assertFalse(c.mcpService.isActive(installed.endpoints.single().id))
                assertTrue(c.pluginService.requestReadiness(session).contains("NOT_CONNECTED"))
            }
            assertEquals(
                "Draft only",
                c.skillRepository
                    .read(installed.skills.single(), session)
                    .body
                    .trim(),
            )
            c.pluginService.selectFromUser(session, installed.id, false)
            assertTrue(c.pluginService.requestReadiness(session).contains("NOT_SELECTED"))
        } finally {
            c.pluginService.remove(installed)
            c.storage.deleteSessionPermanently(session)
        }
    }

    @Test fun pureSkillPackageReadsResourcesOnlyWhileItsSourceIsSelected() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val name = "pure-flow-${UUID.randomUUID()}"
        val bundle =
            PluginPackageReader().parse(
                mapOf(
                    ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                    "skills/$name/SKILL.md" to
                        "---\nname: $name\ndescription: Pure workflow\n---\nDraft only".toByteArray(),
                    "skills/$name/references/check.txt" to "reference".toByteArray(),
                ),
            )
        val installed = c.pluginService.install(bundle, "fixture:$name")
        val session = UUID.randomUUID().toString()
        c.storage.sessions.create(session, "Pure workflow fixture", null, null, 0)
        val key = installed.skills.single()
        try {
            assertTrue(installed.endpoints.isEmpty())
            c.pluginService.setSkillEnabled(key, true)
            assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key, session) }
            c.pluginService.selectFromUser(session, installed.id, true)
            assertTrue(
                c.pluginService
                    .sessionRows(session)
                    .single { it.id == installed.id }
                    .ready,
            )
            assertEquals(
                "Draft only",
                c.skillRepository
                    .read(key, session)
                    .body
                    .trim(),
            )
            assertEquals("reference", c.skillRepository.readResource(key, "references/check.txt", session).content)
            c.pluginService.selectFromUser(session, installed.id, false)
            assertFalse(c.skillRepository.list(session).any { it.key == key })
            assertThrows(IllegalArgumentException::class.java) {
                c.skillRepository.readResource(key, "references/check.txt", session)
            }
            assertEquals(
                "Draft only",
                c.skillRepository
                    .inspect(key)
                    .body
                    .trim(),
            )
        } finally {
            c.pluginService
                .list()
                .filter { it.id == installed.id }
                .forEach(c.pluginService::remove)
            c.storage.deleteSessionPermanently(session)
        }
    }
}
