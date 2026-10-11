package com.helix.app.plugin

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.core.model.AgentMode
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.plugin.PluginComponentState
import com.helix.extensions.plugin.executionContext
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BrowserPluginContributionDeviceTest {
    @Test
    @Suppress("LongMethod") // One owned Activity journey keeps selection/default and manual-tab assertions together.
    fun defaultSelectionRevocationKeepsManualTabsAndUserDefaults() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val plugin = c.pluginService.list().single { it.native?.pluginId == "browser" }
        val bindings =
            c.toolPipeline.registry
                .snapshot()
                .filter {
                    it.descriptor.name.value
                        .startsWith("browser.")
                }
        assertEquals(12, bindings.size)
        assertTrue(bindings.all { it.descriptor.origin == ToolOrigin.PluginOrigin("browser", "0.1.0", "browser") })
        val first = UUID.randomUUID().toString()
        c.storage.sessions.create(first, "Browser contribution fixture", null, null, 0)
        c.sessionPermissionEdit.saveSessionConfig(
            first,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            System.currentTimeMillis(),
        )
        var next: String? = null
        var tabId: String? = null
        try {
            assertNotNull(c.pluginRegistry.executionContext(bindings.first().descriptor, first))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    tabId = c.browser.openTab("data:text/html,<html><body>Manual fixture</body></html>").tabId
                }
                val id = requireNotNull(tabId)
                var originalView: android.webkit.WebView? = null
                scenario.onActivity { originalView = c.browser.hostView(id) }
                awaitLoaded(scenario, c, id)
                dispatchSnapshot(c, first, id, selected = true)
                c.pluginService.selectFromUser(first, plugin.id, false)
                assertNull(c.pluginRegistry.executionContext(bindings.first().descriptor, first))
                assertFalse(c.toolPipeline.disabledToolFilter!!.invoke(first, bindings.first().descriptor))
                dispatchSnapshot(c, first, id, selected = false)
                scenario.onActivity {
                    assertNotNull(c.browser.tab(id))
                    assertSame(originalView, c.browser.hostView(id))
                    c.browser.navigate(id, "data:text/html,<html><body>Still manual</body></html>")
                }
                c.pluginService.selectFromUser(first, plugin.id, true)
                assertNotNull(c.pluginRegistry.executionContext(bindings.first().descriptor, first))
                c.pluginService.setDefaultFromUser(plugin.id, false)
                c.pluginRegistry.repair("browser")
                val created = UUID.randomUUID().toString()
                c.storage.sessions.create(created, "Browser default fixture", null, null, 0)
                next = created
                assertNull(c.pluginRegistry.executionContext(bindings.first().descriptor, created))
                assertNotNull(c.pluginRegistry.executionContext(bindings.first().descriptor, first))
                scenario.onActivity { c.browser.closeTab(id) }
                tabId = null
            }
            assertFalse(c.browser.hostAvailable())
            val readiness = c.pluginService.componentReadiness(plugin, first)
            assertTrue(readiness.components.any { it.state == PluginComponentState.HOST_UNAVAILABLE })
            // Selecting a capability without an Activity does not create an Activity or WebView.
            c.pluginService.selectFromUser(first, plugin.id, false)
            c.pluginService.selectFromUser(first, plugin.id, true)
            assertFalse(c.browser.hostAvailable())
        } finally {
            c.pluginService.setDefaultFromUser(plugin.id, true)
            next?.let(c.storage::deleteSessionPermanently)
            c.storage.deleteSessionPermanently(first)
            tabId?.let { id ->
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    c.browser.closeTab(id)
                }
            }
        }
    }

    private fun dispatchSnapshot(
        c: AppContainer,
        sessionId: String,
        tabId: String,
        selected: Boolean,
    ) = runBlocking {
        val turn = UUID.randomUUID().toString()
        c.storage.turns.start(turn, sessionId, 0)
        c.chatService.openSession(sessionId)
        try {
            val result =
                c.chatService.dispatchToolCall(
                    UUID.randomUUID().toString(),
                    turn,
                    "browser.snapshot",
                    """{"tabId":"$tabId"}""",
                    mode = AgentMode.ACT,
                )
            if (selected) {
                assertTrue(result.toString(), result is ToolDispatchOutcome.Succeeded)
                assertTrue(result.toString().contains("Manual fixture"))
            } else {
                assertTrue(result.toString(), result is ToolDispatchOutcome.Denied)
                assertEquals(DispatchOutcomeCode.TOOL_DISABLED, (result as ToolDispatchOutcome.Denied).code)
            }
        } finally {
            c.toolPipeline.endTurn(turn)
            c.chatService.closeSession()
        }
    }

    private fun awaitLoaded(
        scenario: ActivityScenario<MainActivity>,
        c: AppContainer,
        tabId: String,
    ) {
        var loaded = false
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (!loaded && android.os.SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { loaded = c.browser.tab(tabId)?.isLoading == false }
            if (!loaded) android.os.SystemClock.sleep(50)
        }
        assertTrue("Synthetic browser page did not load", loaded)
    }
}
