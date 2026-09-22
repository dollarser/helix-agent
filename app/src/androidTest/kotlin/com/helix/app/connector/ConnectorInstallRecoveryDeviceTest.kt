package com.helix.app.connector

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The host restarts the actual app after a kill at the requested install boundary. */
class ConnectorInstallRecoveryDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val marker get() = File(app.filesDir, "connector-install-recovery-fixture.txt")
    private val identity = "test:connector-process-recovery"
    private val session = "connector-process-recovery-session"

    @Test
    fun prepare() {
        val phase = requireNotNull(InstrumentationRegistry.getArguments().getString("recoveryPhase"))
        require(phase in setOf("preparing", "before-commit", "after-commit"))
        val old = c.connectorService.install(bundle("old"), identity)
        c.storage.sessions.create(session, "Recovery", null, null, 0)
        c.connectorService.catalog.select(session, old.id, true)
        c.connectorService.setSkillEnabled(old.skills.single(), true)
        c.storage.secrets.put(SecretAlias(old.endpoints.single().id), "synthetic-recovery")
        val fields = listOf(phase, Process.myPid().toString(), old.id, old.hash, old.endpoints.single().id)
        marker.writeText(fields.joinToString("\n"))
        val service =
            ConnectorService(
                app,
                c.storage,
                c.mcpService,
                c.skillImportService,
                c.skillRepository,
                c.mcpOAuthCoordinator,
                c.connectorService.catalog,
                installBoundary = { boundary ->
                    if (boundary == phase) {
                        Process.killProcess(Process.myPid())
                        error("Process kill unexpectedly returned")
                    }
                },
            )
        service.install(bundle("new"), identity, old.revision)
        error("Requested boundary was not reached")
    }

    @Test
    fun verify() {
        val fields = marker.readLines()
        assertNotEquals(fields[1].toInt(), Process.myPid())
        val installed = c.connectorService.list().single { it.identity == identity }
        assertEquals(fields[2], installed.id)
        assertEquals(if (fields[0] == "after-commit") bundle("new").contentHash else fields[3], installed.hash)
        assertEquals("synthetic-recovery", c.storage.secrets.get(SecretAlias(fields[4])))
        c.connectorService.cleanupRetired()
        assertEquals(setOf(installed.id), c.connectorService.catalog.selected(session))
        assertTrue(
            c.skillRepository.read(installed.skills.single(), session).body.contains(
                if (fields[0] == "after-commit") "new" else "old",
            ),
        )
        assertTrue(
            app.cacheDir
                .listFiles()
                .orEmpty()
                .none { it.name.startsWith("connector-skills-") },
        )
        c.connectorService.remove(installed)
        c.storage.deleteSessionPermanently(session)
        marker.delete()
    }

    private fun bundle(body: String): com.helix.extensions.skills.connector.ConnectorPackage {
        val skill = "---\nname: recovery\ndescription: Recovery fixture\n---\n$body".toByteArray()
        val endpoint = """{"mcp_servers":{"docs":{"url":"https://example.com/mcp"}}}"""
        return ConnectorPackageReader().parse(
            mapOf(
                ".codex-plugin/plugin.json" to """{"name":"recovery"}""".toByteArray(),
                ".mcp.json" to endpoint.toByteArray(),
                "skills/recovery/SKILL.md" to skill,
            ),
        )
    }
}
