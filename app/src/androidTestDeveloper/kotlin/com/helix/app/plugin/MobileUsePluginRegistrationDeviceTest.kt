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
