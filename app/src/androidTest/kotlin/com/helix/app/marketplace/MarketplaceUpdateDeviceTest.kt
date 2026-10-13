package com.helix.app.marketplace

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class MarketplaceUpdateDeviceTest {
    @Test
    fun jsonIndexRequiresMatchingContentUriPackageAndCancelledInstallPublishesNothing() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val workspace = app.filesDir.toPath().resolve("workspaces")
        Files.createDirectories(workspace)
        val root = Files.createTempDirectory(workspace, "market-json-")
        val name = "json-package-${UUID.randomUUID().toString().take(8)}"
        val service =
            MarketplaceService(c.pluginService, c.skillRepository, MarketplaceSourceStore(root.resolve("sources")))
        try {
            val index = root.resolve("market.json")
            Files.writeString(
                index,
                """{"name":"$name-index","plugins":[
                  {"name":"$name","source":{"source":"github","repo":"example/$name"}}]}""",
            )
            val indexUri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", index.toFile())
            service.imports.addSource(service.imports.previewSource(indexUri))
            val item = service.items().single { it.imported?.entry?.name == name }
            assertThrows(IllegalArgumentException::class.java) { service.imports.previewItem(item) }
            org.junit.Assert.assertNull(service.findInstalled(item))
            val archive = root.resolve("package.zip")
            java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                mapOf(
                    ".codex-plugin/plugin.json" to """{"name":"$name","version":"1"}""",
                    "skills/$name/SKILL.md" to
                        "---\nname: $name\ndescription: Owned JSON selection\n---\nReview this fixture.",
                ).forEach { (path, text) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", archive.toFile())
            val bundle = service.imports.previewProvided(item, uri)
            assertThrows(IllegalStateException::class.java) {
                service.imports.installCandidate(item, bundle, null) { true }
            }
            org.junit.Assert.assertNull(service.findInstalled(item))
            val installed = service.imports.installCandidate(item, bundle, null)
            assertEquals(name, installed.name)
            assertEquals(installed.id, service.findInstalled(item)?.id)
        } finally {
            c.pluginService
                .list()
                .filter { it.name == name }
                .forEach(c.pluginService::remove)
            removeMarketplaceFixture(root)
        }
    }

    @Test
    @Suppress("LongMethod") // Owned source/version/selection lifecycle and cleanup are one atomic test journey.
    fun sourceUpdatesRequireFreshCandidateAndInstalledRevision() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "market-update-")
        val service =
            MarketplaceService(c.pluginService, c.skillRepository, MarketplaceSourceStore(root.resolve("sources")))
        val name = "market-update-${UUID.randomUUID().toString().take(8)}"
        val session = "hxa282-${UUID.randomUUID()}"
        try {
            val first = service.imports.previewSource(writeMarketplaceFixture(root, name, "1.0"))
            service.imports.addSource(first)
            val item1 = service.items().single { it.imported?.entry?.name == name }
            val bundle1 = service.imports.previewItem(item1)
            val installed1 = service.imports.installCandidate(item1, bundle1, null)
            c.storage.sessions.create(session, "Owned market update", null, null, 0)
            c.pluginService.selectFromUser(session, installed1.id, true)
            val selection = c.storage.connectors.selectionId(session, installed1.id)
            val second = service.imports.previewSource(writeMarketplaceFixture(root, name, "2.0"))
            service.imports.addSource(second)
            assertEquals("1.0", service.findInstalled(item1)?.versionLabel)
            assertThrows(IllegalArgumentException::class.java) { service.imports.previewItem(item1) }
            val item2 = service.items().single { it.imported?.entry?.name == name }
            val bundle2 = service.imports.previewItem(item2)
            assertThrows(IllegalStateException::class.java) {
                service.imports.installCandidate(item2, bundle2, installed1.revision) { true }
            }
            assertEquals(installed1, service.findInstalled(item2))
            val installed2 = service.imports.installCandidate(item2, bundle2, installed1.revision)
            assertEquals(installed1.id, installed2.id)
            assertEquals("2.0", installed2.versionLabel)
            assertNotEquals(installed1.hash, installed2.hash)
            assertEquals(selection, c.storage.connectors.selectionId(session, installed2.id))
            assertEquals(installed2, service.imports.installCandidate(item2, bundle2, installed1.revision))
            service.imports.addSource(service.imports.previewSource(writeMarketplaceFixture(root, name, "3.0")))
            val item3 = service.items().single { it.imported?.entry?.name == name }
            assertThrows(IllegalArgumentException::class.java) {
                service.imports.installCandidate(item3, service.imports.previewItem(item3), installed1.revision)
            }
            assertEquals(installed2, service.findInstalled(item3))
            service.imports.addSource(service.imports.previewSource(writeMarketplaceFixture(root, name, "1.0")))
            val downgrade = service.items().single { it.imported?.entry?.name == name }
            val restored =
                service.imports.installCandidate(
                    downgrade,
                    service.imports.previewItem(downgrade),
                    installed2.revision,
                )
            assertEquals(installed1.id, restored.id)
            assertEquals("1.0", restored.versionLabel)
            assertEquals(selection, c.storage.connectors.selectionId(session, restored.id))
        } finally {
            if (c.storage.sessions.find(session) != null) c.storage.deleteSessionPermanently(session)
            c.pluginService
                .list()
                .filter { it.name == name }
                .forEach(c.pluginService::remove)
            removeMarketplaceFixture(root)
        }
    }
}
