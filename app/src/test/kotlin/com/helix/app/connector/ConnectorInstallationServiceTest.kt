package com.helix.app.connector

import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConnectorInstallationServiceTest {
    @Test
    fun jsonAndZipPreviewStripCredentialsAndBindIdenticalPortableBytes() =
        fixture { root, service ->
            val json = """{"docs":{"url":"https://example.test/mcp","headers":{"Authorization":"fixture-value"}}}"""
            Files.write(root.resolve("workspace/work/mcp.json"), json.toByteArray())
            ZipOutputStream(Files.newOutputStream(root.resolve("workspace/work/connector.zip"))).use { zip ->
                zip.putNextEntry(ZipEntry("mcp.json"))
                zip.write(json.toByteArray())
                zip.closeEntry()
            }
            val preview = service.preview("scope:app:work/mcp.json")
            assertEquals(preview.contentHash, service.preview("scope:app:work/connector.zip").contentHash)
            assertTrue(preview.endpoints.single().needsCredential)
            assertFalse(preview.toString().contains("fixture-value"))
            Files.write(root.resolve("workspace/work/mcp.json"), json.replace("/mcp", "/changed").toByteArray())
            assertNotEquals(preview.contentHash, service.preview("scope:app:work/mcp.json").contentHash)
            assertThrows(IllegalArgumentException::class.java) {
                service.install("scope:app:work/mcp.json", preview.contentHash)
            }
        }

    @Test
    fun invalidOrCancelledPreviewNeverReachesInstallerAndLeavesNoCopies() =
        fixture { root, service ->
            Files.write(root.resolve("workspace/work/mcp.json"), "bad json".toByteArray())
            assertThrows(IllegalStateException::class.java) { service.preview("scope:app:work/mcp.json") }
            assertThrows(IllegalStateException::class.java) { service.preview("scope:app:work/mcp.json") { true } }
            assertThrows(IllegalArgumentException::class.java) { service.preview("scope:app:.helix/private.json") }
            Files.list(root.resolve("temporary")).use { assertEquals(0L, it.count()) }
        }

    private fun fixture(test: (Path, ConnectorInstallationService) -> Unit) {
        val root = Files.createTempDirectory("connector-install-test-")
        try {
            val store = WorkspaceArtifactStore(ScopeRootResolver { root.resolve("workspace") })
            store.ensureLayout("app")
            test(root, ConnectorInstallationService(store, root.resolve("temporary")) { error("No install expected") })
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
