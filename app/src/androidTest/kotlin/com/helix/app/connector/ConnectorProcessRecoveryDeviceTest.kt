package com.helix.app.connector

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.extensions.skills.connector.ConnectorPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID

/** Two explicit instrumentation invocations separated by force-stop in the HXA-124 driver. */
@RunWith(AndroidJUnit4::class)
class ConnectorProcessRecoveryDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val marker get() = app.filesDir.toPath().resolve("connector-process-test.txt")

    @Test
    fun seedPersistedConnector() {
        val name = "recovery-${UUID.randomUUID()}"
        val bundle =
            ConnectorPackageReader().parse(
                mapOf(
                    ".codex-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                    "skills/$name/SKILL.md" to
                        "---\nname: $name\ndescription: Recovery fixture\n---\nRead resources.\n".toByteArray(),
                    ".mcp.json" to """{"docs":{"url":"https://connector.invalid/mcp"}}""".toByteArray(),
                ),
            )
        val service = app.appContainer.connectorService
        val installed = service.install(bundle)
        service.setSkillEnabled(installed.skills.single(), true)
        Files.write(marker, "${installed.id}\n${Process.myPid()}".toByteArray())
        assertTrue(service.skillEnabled(installed.skills.single()))
    }

    @Test
    fun recoverInNewProcess() {
        val lines = Files.readAllLines(marker)
        assertNotEquals(lines[1].toInt(), Process.myPid())
        val service = app.appContainer.connectorService
        val record = service.list().single { it.id == lines[0] }
        try {
            assertTrue(service.skillEnabled(record.skills.single()))
            assertEquals(
                "Read resources.",
                app.appContainer.skillRepository
                    .read(record.skills.single())
                    .body
                    .trim(),
            )
            assertFalse(service.enabled(record.endpoints.single()))
        } finally {
            service.remove(record)
            Files.delete(marker)
        }
    }
}
