package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Actual APK content/import persistence only; never enables accessibility or operates another app. */
class MobileUsePackageDeviceTest {
    @Test fun actualMobileUseContentImportsAndSavesWithoutGrantingNativeAuthority() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val service = app.appContainer.pluginService
        val files = linkedMapOf<String, ByteArray>()

        fun collect(path: String) {
            val assetPath = if (path.isEmpty()) "plugins/mobile-use" else "plugins/mobile-use/$path"
            val children = requireNotNull(app.assets.list(assetPath))
            if (children.isEmpty()) {
                files[path] = app.assets.open(assetPath).use { it.readBytes() }
            } else {
                children.forEach { collect(if (path.isEmpty()) it else "$path/$it") }
            }
        }
        collect("")
        val bundle = PluginPackageReader().parse(files)
        assertEquals("mobile-use", bundle.name)
        assertTrue(bundle.componentDiagnostics.any { it.kind == PluginComponentKind.NATIVE && it.omitted })
        val identity = "fixture:mobile-use:${UUID.randomUUID()}"
        val record = service.install(bundle, identity)
        try {
            assertNull(record.native)
            val key = record.skills.single()
            assertTrue(service.inspectSkill(key).contains("name: android-ui-task"))
            val saved = service.install(bundle, identity, expectedRevision = record.revision)
            assertEquals(record.id, saved.id)
            assertEquals(bundle.contentHash, saved.hash)
            assertTrue(service.inspectSkill(saved.skills.single()).contains("never permission"))
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
        }
    }
}
