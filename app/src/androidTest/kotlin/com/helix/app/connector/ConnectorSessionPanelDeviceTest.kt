package com.helix.app.connector

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ConnectorSessionPanelDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sessionSelectionDoesNotEditGlobalNewSessionDefaults() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val service = c.pluginService
        val session = "connector-ui-${UUID.randomUUID()}"
        val next = "$session-next"
        val record =
            service.install(
                PluginPackageReader().readJson(
                    """{"mcp_servers":{"fixture":{"url":"https://example.com/ui"}}}""".toByteArray(),
                ),
                identity = session,
            )
        c.storage.sessions.create(session, "UI", null, null, 0)
        val initiallySelected = service.catalog.selected(session)
        try {
            compose.setContent {
                MaterialTheme { ConnectorSessionPanel(service, session, {}, {}) }
            }
            val selected = "connector-session-${record.id}"
            val default = "connector-default-${record.id}"
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(selected).fetchSemanticsNodes().isNotEmpty() }
            compose
                .onNodeWithTag(selected)
                .performScrollTo()
                .assertIsOff()
                .performClick()
            compose.waitUntil(10_000) { record.id in service.catalog.selected(session) }
            compose.onNodeWithTag(selected).assertIsOn()
            compose.onNodeWithTag(default).assertDoesNotExist()
            service.catalog.setDefault(record.id, true)
            c.storage.sessions.create(next, "New", null, null, 0)
            compose.onNodeWithTag(selected).performScrollTo().performClick()
            compose.waitUntil(10_000) { service.catalog.selected(session) == initiallySelected }
            assertEquals(initiallySelected + record.id, service.catalog.selected(next))
            assertTrue(service.catalog.defaultSelected(record.id))
        } finally {
            service.remove(record)
            c.storage.deleteSessionPermanently(session)
            c.storage.deleteSessionPermanently(next)
        }
    }
}
