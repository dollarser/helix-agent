package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.connector.ConnectorSessionPanel
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real picker → explanation → target plugin configuration → original picker; only synthetic local content. */
class PluginConfigurationReturnDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedConfigurationReturnsSelectedAndApplyPersistsIt() = verifyReturn(true)

    @Test fun incompleteConfigurationReturnsUnselected() = verifyReturn(false)

    private fun verifyReturn(complete: Boolean) {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val service = c.pluginService
        val name = "repair-${UUID.randomUUID()}"
        val record =
            service.install(
                PluginPackageReader().parse(
                    mapOf(
                        ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                        "skills/$name/SKILL.md" to
                            "---\nname: $name\ndescription: Repair fixture\n---\nSynthetic only".toByteArray(),
                    ),
                ),
                identity = name,
            )
        c.storage.sessions.create(name, "Repair return fixture", null, null, 0)
        try {
            service.setSkillEnabled(record.skills.single(), false)
            compose.setContent {
                MaterialTheme {
                    RepairHost(service, name, record.id)
                }
            }
            val tag = "connector-session-${record.id}"
            waitTag(tag)
            compose
                .onNodeWithTag(tag)
                .assertIsOff()
                .assertIsEnabled()
                .performClick()
            waitTag("plugin-setup-continue")
            compose.onNodeWithTag("plugin-setup-continue").performClick()
            waitTag("plugin-configuration-back")
            assertFalse(record.id in service.catalog.selected(name))
            if (complete) {
                completeConfiguration(service, record, name)
            }
            compose.onNodeWithTag("plugin-configuration-back").performScrollTo().performClick()
            waitTag(tag)
            if (complete) {
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag).assertIsOn() }.isSuccess }
                assertFalse(record.id in service.catalog.selected(name))
                compose.onNodeWithTag("plugin-session-apply").performClick()
                compose.waitUntil(5_000) { record.id in service.catalog.selected(name) }
            } else {
                compose.onNodeWithTag(tag).assertIsOff()
                assertFalse(record.id in service.catalog.selected(name))
            }
        } finally {
            service.remove(record)
            c.storage.deleteSessionPermanently(name)
        }
    }

    private fun completeConfiguration(
        service: com.helix.app.plugin.PluginService,
        record: com.helix.app.plugin.InstalledPlugin,
        name: String,
    ) {
        waitTag("plugin-tab-1-${record.id}")
        compose.onNodeWithTag("plugin-tab-1-${record.id}").onParent().performScrollTo()
        compose.onNodeWithTag("plugin-tab-1-${record.id}").performScrollTo().performClick()
        val skillTag = "plugin-skill-enabled-$name"
        waitTag(skillTag)
        compose
            .onNodeWithTag(skillTag)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.waitUntil(5_000) { service.skillEnabled(record.skills.single()) }
    }

    @androidx.compose.runtime.Composable
    @Suppress("FunctionName")
    private fun RepairHost(
        service: com.helix.app.plugin.PluginService,
        name: String,
        pluginId: String,
    ) {
        val nav = rememberNavController()
        NavHost(nav, "picker") {
            composable("picker") { source ->
                ConnectorSessionPanel(
                    service,
                    name,
                    { id, reason -> nav.configurePluginFrom(source, id, reason) },
                    pluginId = pluginId,
                    canApply = { nav.currentBackStackEntry?.id == source.id },
                    onDismiss = {},
                )
            }
            pluginConfigurationDestination(nav, service)
        }
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
