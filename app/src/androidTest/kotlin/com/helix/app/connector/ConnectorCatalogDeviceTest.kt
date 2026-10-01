package com.helix.app.connector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.chat.SessionFork
import com.helix.app.plugin.InstalledPlugin
import com.helix.app.plugin.PluginCatalog
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ConnectorCatalogDeviceTest {
    @Test
    fun forkCopiesSelectionInsteadOfDefaultsAndDeletionCascadesAfterReopen() {
        withStorage { context, name, root, storage ->
            val catalog = PluginCatalog(storage)

            fun record(id: String) =
                InstalledPlugin(
                    id = id,
                    name = id,
                    source = "LOCAL",
                    hash = id,
                    endpoints = emptyList(),
                    skills = emptyList(),
                    diagnostics = emptyList(),
                    identity = "local:test:$id",
                    revision = 1,
                    sessionScoped = true,
                )
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
                val recovered = PluginCatalog(reopened)
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
