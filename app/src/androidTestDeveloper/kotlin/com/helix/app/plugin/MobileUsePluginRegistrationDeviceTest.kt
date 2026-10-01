package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.tools.framework.ToolOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App-level proof that Mobile Use is a Plugin contribution, not a renamed built-in tool group. */
class MobileUsePluginRegistrationDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer

    @Test
    fun nativeDisableRepairAndReenableUseTheProductionCatalog() {
        val service = container.pluginService
        val record = service.list().single { it.native?.pluginId == "mobile-use" }
        val tools = container.toolPipeline.registry
        val session =
            java.util.UUID
                .randomUUID()
                .toString()
        container.storage.sessions.create(session, "Native fixture", null, null, 0)
        try {
            service.setEnabled(record.id, true)
            val old = tools.snapshot().first { it.descriptor.origin is ToolOrigin.PluginOrigin }.ref
            service.catalog.select(session, record.id, false)
            val source = requireNotNull(tools.resolveBinding(old)).descriptor.origin.canonicalOf()
            assertEquals(false, service.catalog.sourceAvailable(source, session))
            service.catalog.select(session, record.id, true)
            assertEquals(true, service.catalog.sourceAvailable(source, session))
            service.setEnabled(record.id, false)
            service.repairNative(record.id)
            assertEquals(false, service.list().single { it.id == record.id }.enabled)
            assertEquals(null, tools.resolveBinding(old))
            service.setEnabled(record.id, true)
            assertEquals(true, container.pluginRegistry.ready("mobile-use"))
            assertEquals(null, tools.resolveBinding(old))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                service.remove(service.list().single { it.id == record.id })
            }
        } finally {
            service.setEnabled(record.id, record.enabled)
            container.storage.deleteSessionPermanently(session)
        }
    }

    @Test
    fun mobileUseRegistersOnceWithPluginProvenance() {
        val manifest = container.pluginRegistry.find("mobile-use")
        assertNotNull(manifest)
        assertEquals("0.1.0", manifest?.version)
        assertEquals("mobile-use", manifest?.helixRuntimeId)

        val uiTools =
            container.toolPipeline.registry
                .all()
                .filter { it.name.value.startsWith("ui.") }
        assertEquals(
            setOf(
                "ui.snapshot",
                "ui.find",
                "ui.click",
                "ui.long_click",
                "ui.set_text",
                "ui.scroll",
                "ui.back",
                "ui.home",
                "ui.wait",
            ),
            uiTools.map { it.name.value }.toSet(),
        )
        val expected = ToolOrigin.PluginOrigin("mobile-use", "0.1.0", "mobile-use")
        assertTrue(uiTools.all { it.origin == expected })
        assertTrue(uiTools.all { it.contractHash.hex.length == 64 })
    }
}
