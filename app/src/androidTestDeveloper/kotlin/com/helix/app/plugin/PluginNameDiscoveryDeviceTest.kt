package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production registry + service projections; discovery does not invoke a native executor. */
class PluginNameDiscoveryDeviceTest {
    @Test fun installedNamesAndNativeToolSearchUseOneCatalogWithoutChangingAvailability() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            app.runtimeRecoveryReady.await()
            val c = app.appContainer
            val installed = c.pluginService.list()
            val session = "plugin-names-discovery-${System.nanoTime()}"
            c.storage.sessions.create(session, "Plugin names discovery fixture", null, null, System.currentTimeMillis())
            try {
                val before = c.pluginService.catalog.effectiveSelected(session)
                val rows =
                    c.pluginService
                        .requestReadiness(session)
                        .plugins
                        .associateBy { it.pluginId }
                for (plugin in installed.filter { it.native != null }) {
                    val name = plugin.pluginName()
                    assertEquals(name.aliases, rows.getValue(plugin.id).name.aliases)
                }
                val registry = c.toolPipeline.registry
                val discovery =
                    com.helix.app.mcp
                        .McpToolDiscovery(registry)
                for ((id, query) in listOf("mobile-use" to "手机操作", "browser" to "浏览器", "documents" to "文档")) {
                    val chinese = discovery.search(session, query, 16, source = "plugin:$id")
                    val english =
                        discovery.search(
                            session,
                            PluginName(id, id).displayName(listOf("en")),
                            16,
                            source = "plugin:$id",
                        )
                    assertTrue("No registered tools for $id", chinese.isNotEmpty())
                    assertEquals(english.map { it.name }.toSet(), chinese.map { it.name }.toSet())
                    assertTrue(chinese.all { (it.origin as? ToolOrigin.PluginOrigin)?.pluginId == id })
                }
                val unavailable =
                    com.helix.app.mcp
                        .McpToolDiscovery(registry) { _, _ -> false }
                assertTrue(unavailable.search(session, "手机操作", 16).isEmpty())
                assertEquals(before, c.pluginService.catalog.effectiveSelected(session))
                assertEquals(installed, c.pluginService.list())
            } finally {
                c.storage.deleteSessionPermanently(session)
            }
        }
}
