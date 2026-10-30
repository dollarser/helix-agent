package com.helix.app.connector

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.extensions.plugin.PluginPackageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Synthetic installation only: never authenticates or saves input into a real connector. */
class ConnectorAuthModeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bearerSwitchEditingAndDismissalPreserveStoredConfiguration() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val service = app.awaitAppContainer().pluginService
            val identity = "auth-ui-fixture:${UUID.randomUUID()}"
            val record =
                withContext(Dispatchers.IO) {
                    service.install(
                        PluginPackageReader().parse(
                            mapOf(
                                ".codex-plugin/plugin.json" to """{"name":"auth-ui-fixture"}""".toByteArray(),
                                ".mcp.json" to
                                    """{"mcp_servers":{
                        "github":{"url":"https://api.githubcopilot.com/mcp/"}
                    }}""".toByteArray(),
                            ),
                        ),
                        identity,
                    )
                }
            val endpoint = record.endpoints.single()
            val visible = mutableStateOf(true)
            try {
                compose.setContent {
                    MaterialTheme {
                        if (visible.value) EndpointRow(service, endpoint) { error("No connection test requested") }
                    }
                }
                repeat(2) {
                    compose.onNodeWithTag("connector-auth-oauth-${endpoint.id}").performClick()
                    compose.onNodeWithTag("connector-auth-bearer-${endpoint.id}").performClick()
                    compose.waitUntil {
                        val node = compose.onNodeWithTag("connector-auth-oauth-${endpoint.id}").fetchSemanticsNode()
                        !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                    }
                    compose.onNodeWithTag("connector-bearer-input-${endpoint.id}").assertIsDisplayed()
                }
                compose.onNodeWithTag("connector-bearer-input-${endpoint.id}").performTextInput("synthetic-not-a-token")
                compose.runOnIdle { visible.value = false }
                compose.runOnIdle { visible.value = true }
                compose.onNodeWithTag("connector-auth-oauth-${endpoint.id}").assertIsEnabled()
                withContext(Dispatchers.IO) {
                    assertEquals(record, service.list().single { it.id == record.id })
                }
            } finally {
                compose.runOnIdle { visible.value = false }
                withContext(Dispatchers.IO) { service.remove(record) }
            }
        }
}
