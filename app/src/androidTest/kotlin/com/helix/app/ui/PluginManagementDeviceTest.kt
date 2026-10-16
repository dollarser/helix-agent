package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.plugin.InstalledPlugin
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real installed records and native content; no external connection or provider request. */
class PluginManagementDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val container get() = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
    private val service get() = container.pluginService

    @Test fun nativePluginsShareSetupContentAndDetailsWithoutChangingDefaults() {
        val records = service.list().filter { it.native?.pluginId in setOf("browser", "documents") }
        assertEquals(2, records.size)
        val defaults = records.associate { it.id to service.catalog.defaultSelected(it.id) }
        compose.setContent { MaterialTheme { ExtensionsScreen(null, null, service) } }
        records.forEach { record ->
            open(record)
            compose.onNodeWithTag("connector-default-${record.id}").performScrollTo().assertIsDisplayed()
            tab(record, 1)
            waitFor("plugin-required-heading-${record.native!!.pluginId}")
            compose.onNodeWithTag("plugin-required-heading-${record.native.pluginId}").performScrollTo().performClick()
            compose.onNodeWithTag("plugin-required-content-${record.native.pluginId}").assertExists()
            tab(record, 2)
            compose.onNodeWithTag("plugin-remove-${record.id}").assertDoesNotExist()
            tab(record, 0)
            assertEquals(defaults[record.id], service.catalog.defaultSelected(record.id))
        }
    }

    @Test fun installedSetupIsVisibleAndUseTargetsOnlyThatPlugin() {
        val record = install()
        val session = UUID.randomUUID().toString()
        container.storage.sessions.create(session, "Plugin selection fixture", null, null, 0)
        try {
            compose.setContent {
                MaterialTheme { ExtensionsScreen(null, null, service, prepareSession = { session }) }
            }
            open(record)
            compose
                .onNodeWithTag("plugin-connection-${record.endpoints.single().id}")
                .performScrollTo()
                .assertIsDisplayed()
            tab(record, 1)
            compose.onNodeWithTag("plugin-use-${record.id}").performScrollTo().performClick()
            waitFor("connector-session-${record.id}")
            compose.onNodeWithTag("connector-session-bundled-plugin:browser").assertDoesNotExist()
            assertFalse(record.id in service.catalog.selected(session))
            assertFalse(service.enabled(record.endpoints.single()))
            compose.onNodeWithTag("plugin-session-configure-${record.id}").performScrollTo().performClick()
            compose.onNodeWithTag("connector-session-${record.id}").assertDoesNotExist()
            compose
                .onNodeWithTag("plugin-connection-${record.endpoints.single().id}")
                .performScrollTo()
                .assertIsDisplayed()
            assertFalse(record.id in service.catalog.selected(session))
        } finally {
            container.storage.deleteSessionPermanently(session)
            service.remove(record)
        }
    }

    @Test fun removalRequiresConfirmationAndCancelKeepsInstallation() {
        val record = install()
        try {
            compose.setContent { MaterialTheme { ExtensionsScreen(null, null, service) } }
            open(record)
            tab(record, 2)
            compose.onNodeWithTag("plugin-remove-${record.id}").performScrollTo().performClick()
            compose.onNodeWithText(containerContext().getString(com.helix.app.R.string.common_cancel)).performClick()
            assertTrue(service.list().any { it.id == record.id })
            compose.onNodeWithTag("plugin-remove-${record.id}").performScrollTo().performClick()
            compose.onNodeWithTag("plugin-remove-confirm-${record.id}").performClick()
            compose.waitUntil(10_000) { service.list().none { it.id == record.id } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("plugin-card-${record.id}").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("plugin-card-${record.id}").assertDoesNotExist()
        } finally {
            service.list().find { it.id == record.id }?.let { service.remove(it) }
        }
    }

    @Test fun selectedManagementSectionSurvivesRecreationWithoutEnablingPlugin() {
        val record = service.list().single { it.native?.pluginId == "browser" }
        val before = service.catalog.defaultSelected(record.id)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ExtensionsScreen(null, null, service) } }
        open(record)
        tab(record, 1)
        restoration.emulateSavedInstanceStateRestore()
        waitFor("plugin-required-heading-browser")
        compose.onNodeWithTag("plugin-required-heading-browser").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connector-default-${record.id}").assertDoesNotExist()
        assertEquals(before, service.catalog.defaultSelected(record.id))
    }

    private fun containerContext() = ApplicationProvider.getApplicationContext<HelixApplication>()

    private fun install(): InstalledPlugin {
        val name = "management-${UUID.randomUUID()}"
        return service.install(
            PluginPackageReader().parse(
                mapOf(
                    ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                    ".mcp.json" to """{"mcpServers":{"fixture":{"url":"https://example.invalid/mcp"}}}""".toByteArray(),
                    "skills/$name/SKILL.md" to "---\nname: $name\ndescription: Fixture\n---\nDraft only".toByteArray(),
                ),
            ),
            "fixture:$name",
        )
    }

    private fun open(record: InstalledPlugin) {
        val tag = record.native?.let { "bundled-plugin-${it.pluginId}" } ?: "extension-manage-${record.id}"
        waitFor(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun tab(
        record: InstalledPlugin,
        index: Int,
    ) {
        compose.onNodeWithTag("plugin-tab-$index-${record.id}").performScrollTo().performClick()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
