package com.helix.app.connector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.chat.SessionFork
import com.helix.core.storage.HelixStorage
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ConnectorCatalogMigrationDeviceTest {
    @Test
    fun legacyMarketplaceAdoptionRequiresTrustedSourceAndExactCurrentCatalogueHash() {
        val item =
            com.helix.app.marketplace.MarketplaceCatalog
                .items()
                .first()
        val bundle =
            com.helix.extensions.skills.connector
                .ConnectorPackageReader()
                .readJson(item.payload.toByteArray())
        val record =
            InstalledConnector(
                "legacy-market",
                requireNotNull(item.targetConnectorName),
                "MARKETPLACE",
                bundle.contentHash,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        assertEquals("marketplace:${item.id}", legacyConnectorIdentity(record).identity)
        assertEquals(record.identity, legacyConnectorIdentity(record.copy(source = "LOCAL")).identity)
        assertEquals(record.identity, legacyConnectorIdentity(record.copy(hash = "0".repeat(64))).identity)
    }

    @Test
    fun legacyImportRunsOnceAndPreservesUnknownIndependentAssetsAcrossReopen() {
        withStorage { context, name, root, storage ->
            val legacy = File(root, "connectors").also { it.mkdirs() }
            val key = SkillKey(SkillSource.USER_IMPORTED, "legacy", "a".repeat(64))
            File(root, "skills/snapshots/${key.name}/${key.snapshotHash}/${key.name}").mkdirs()
            val record =
                InstalledConnector("old", "Legacy", "LOCAL", "b".repeat(64), emptyList(), listOf(key), emptyList())
            File(legacy, "old.json").writeText(encode(record))
            storage.sessions.create("old-session", "Old", null, null, 0)
            val catalog = ConnectorCatalog(storage, legacy.toPath())
            assertEquals(listOf(record), catalog.list())
            assertEquals(setOf("old"), catalog.selected("old-session"))
            catalog.remove(record)
            assertTrue(catalog.skillAvailable(key, "old-session"))
            storage.close()
            HelixStorage.open(context, name, root).useStorage { reopened ->
                val recovered = ConnectorCatalog(reopened, legacy.toPath())
                assertTrue(recovered.list().isEmpty()) // retained JSON must never resurrect an uninstall
                assertTrue(recovered.skillAvailable(key, "old-session"))
                assertEquals(setOf("old"), recovered.selected("old-session"))
            }
        }
    }

    @Test
    fun forkCopiesSelectionInsteadOfDefaultsAndDeletionCascadesAfterReopen() {
        withStorage { context, name, root, storage ->
            val catalog = ConnectorCatalog(storage, File(root, "connectors").toPath())

            fun record(id: String) = InstalledConnector(id, id, "LOCAL", id, emptyList(), emptyList(), emptyList())
            catalog.publish(record("selected"), null)
            catalog.publish(record("default"), null)
            catalog.setDefault("default", true)
            storage.sessions.create("source", "Source", null, null, 0)
            catalog.select("source", "default", false)
            catalog.select("source", "selected", true)
            storage.messages.append("message", "source", null, "USER", "TEXT", "Keep history")
            SessionFork(storage).create("source", "message", "fork", "Fork", 1)
            catalog.select("source", "selected", false)
            assertEquals(setOf("selected"), catalog.selected("fork"))
            assertTrue(catalog.selected("source").isEmpty())
            assertEquals("Keep history", storage.messages.readContent(storage.messages.listBySession("fork").first()))
            storage.close()
            HelixStorage.open(context, name, root).useStorage { reopened ->
                val recovered = ConnectorCatalog(reopened, File(root, "connectors").toPath())
                assertEquals(setOf("selected"), recovered.selected("fork"))
                reopened.deleteSessionPermanently("fork")
                assertFalse(recovered.selected("fork").isNotEmpty())
            }
        }
    }

    private fun withStorage(block: (Context, String, File, HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "connector-catalog-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name).also { it.mkdirs() }
        val storage = HelixStorage.open(context, name, root)
        try {
            block(context, name, root, storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun HelixStorage.useStorage(block: (HelixStorage) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
