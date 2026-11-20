package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.tools.framework.ToolOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classification is a declared contract, never inferred from spelling or wire aliases. */
class BuiltInToolDiscoveryMetadataDeviceTest {
    @Test
    fun registeredHostToolsDeclareTheirDiscoveryCategories() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val descriptors =
            app.appContainer.toolPipeline.registry
                .all()
        val local =
            descriptors.filter {
                it.origin == ToolOrigin.BuiltInOrigin || it.origin is ToolOrigin.PluginOrigin
            }
        assertTrue("Expected production tools", local.isNotEmpty())
        assertTrue(
            "Missing declared categories: " + local.filter { it.discovery.category == null }.map { it.name.value },
            local.all { it.discovery.category != null },
        )
        val categories = local.associate { it.name.value to it.discovery.category }
        assertEquals("files", categories["read"])
        assertEquals("files", categories["files_list"])
        assertEquals("knowledge", categories["knowledge_read"])
        assertEquals("device", categories["device_create_reminder"])
        assertEquals("goals", categories["goal_report"])
        assertEquals("tools", categories["tools_search"])
    }
}
