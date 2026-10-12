package com.helix.app.marketplace

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Prepared for an owner-authorized device run; local fixtures only, no MCP handshake or accounts. */
class MarketplaceSourceLifecycleDeviceTest {
    @Test
    @Suppress("LongMethod") // One owned catalog/install/selection journey with guaranteed cleanup.
    fun catalogRegistrationAndRemovalDoNotInstallOrRemoveSessionPlugins() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val suffix = UUID.randomUUID().toString().take(8)
        val name = "hxa281-plugin-$suffix"
        val market = "hxa281-market-$suffix"
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "hxa281-")
        val service =
            MarketplaceService(c.pluginService, c.skillRepository, MarketplaceSourceStore(root.resolve("sources")))
        val archive = root.resolve("market.zip")
        val session = "hxa281-session-$suffix"
        val before =
            c.pluginService
                .list()
                .map { it.id }
                .toSet()
        try {
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                val files =
                    mapOf(
                        ".claude-plugin/marketplace.json" to """{"name":"$market","plugins":[
                      {"name":"$name","source":"./plugins/$name"}]}""",
                        "plugins/$name/.claude-plugin/plugin.json" to """{
                            "name":"$name","version":"1.0"}""",
                        "plugins/$name/skills/guide-$suffix/SKILL.md" to
                            "---\nname: guide-$suffix\ndescription: Review local fixture\n---\n" +
                            "Read the supplied text only.",
                    )
                files.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            val preview = service.imports.previewSource(Uri.fromFile(archive.toFile()))
            assertEquals(
                before,
                c.pluginService
                    .list()
                    .map { it.id }
                    .toSet(),
            )
            service.imports.addSource(preview)
            assertEquals(
                before,
                c.pluginService
                    .list()
                    .map { it.id }
                    .toSet(),
            )
            val item = service.items().single { it.imported?.entry?.name == name }
            assertEquals(MarketplaceItemStatus.NOT_INSTALLED, service.status(item))
            val bundle = service.imports.previewItem(item)
            assertThrows(
                IllegalStateException::class.java,
            ) { service.imports.installCandidate(item, bundle, null) { true } }
            assertNull(service.findInstalled(item))
            val installed = service.imports.installCandidate(item, bundle, null)
            assertEquals("1.0", installed.versionLabel)
            assertEquals(MarketplaceItemStatus.ACTIVE, service.status(item))
            c.storage.sessions.create(session, "Owned marketplace fixture", null, null, 0)
            assertNull(c.storage.connectors.selectionId(session, installed.id))
            c.pluginService.selectFromUser(session, installed.id, true)
            val selection = c.storage.connectors.selectionId(session, installed.id)
            assertNotNull(selection)
            service.imports.removeSource(preview.catalog)
            assertTrue(service.imports.sources().isEmpty())
            assertEquals(
                installed.id,
                c.pluginService
                    .list()
                    .single { it.identity == installed.identity }
                    .id,
            )
            assertEquals(selection, c.storage.connectors.selectionId(session, installed.id))
        } finally {
            if (c.storage.sessions.find(session) != null) c.storage.deleteSessionPermanently(session)
            c.pluginService
                .list()
                .filter { it.name == name && it.id !in before }
                .forEach(c.pluginService::remove)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
