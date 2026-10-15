package com.helix.app.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.connector.ConnectorSessionPanel
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class MobileUseSelectionSetupDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Before fun requireBackend() = awaitMobileUseTestBackend()

    @Test fun missingScopeOffersConfigurationAndSavingDoesNotSelectAutomatically() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val service = container.pluginService
        val record = service.list().single { it.native?.pluginId == "mobile-use" }
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val original = lines.lines(MobileUseGrantStore.CONFIG_KEY)
        val session = UUID.randomUUID().toString()
        val revision = mutableIntStateOf(0)
        var configure = 0
        container.storage.sessions.create(session, "Selection fixture", null, null, 0)
        try {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, emptyList())
            service.setEnabled(record.id, true)
            compose.setContent {
                androidx.compose.runtime.key(revision.intValue) {
                    MaterialTheme { ConnectorSessionPanel(service, session, { configure++ }, onDismiss = {}) }
                }
            }
            val tag = "connector-session-${record.id}"
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose
                .onNodeWithTag(tag)
                .assertIsOff()
                .assertIsEnabled()
                .performScrollTo()
                .performClick()
            compose.onNodeWithTag("plugin-setup-continue").assertDoesNotExist()
            org.junit.Assert.assertFalse(record.id in service.catalog.selected(session))
            assertEquals(1, configure)
            MobileUseGrantStore(lines::lines, lines::setLines, { null }).configureGlobal(setOf(app.packageName), false)
            compose.runOnIdle { revision.intValue++ }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose
                .onNodeWithTag(tag)
                .assertIsOff()
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(5_000) { record.id in service.catalog.selected(session) }
        } finally {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, original)
            service.setEnabled(record.id, record.enabled)
            container.storage.deleteSessionPermanently(session)
        }
    }

    @Test fun scopedUseReturnsToOriginalConversationAfterSavingConfiguration() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val service = container.pluginService
        val record = service.list().single { it.native?.pluginId == "mobile-use" }
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val original = lines.lines(MobileUseGrantStore.CONFIG_KEY)
        val session = UUID.randomUUID().toString()
        container.storage.sessions.create(session, "Configuration return fixture", null, null, 0)
        service.catalog.select(session, record.id, false)
        try {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, emptyList())
            service.setEnabled(record.id, true)
            compose.setContent {
                MaterialTheme {
                    com.helix.app.ui
                        .ExtensionsScreen(null, null, service, prepareSession = { session })
                }
            }
            waitForTag("bundled-plugin-mobile-use")
            compose.onNodeWithTag("bundled-plugin-mobile-use").performScrollTo().performClick()
            compose.onNodeWithTag("plugin-use-${record.id}").performScrollTo().performClick()
            waitForTag("plugin-session-configure-${record.id}")
            compose.onNodeWithTag("plugin-session-configure-${record.id}").performScrollTo().performClick()
            compose.onNodeWithTag("plugin-setup-continue").assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag("mobile-use-save-settings") and
                            androidx.compose.ui.test
                                .isEnabled(),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("mobile-use-save-settings").performClick()
            waitForTag("connector-session-${record.id}")
            compose.onNodeWithTag("connector-session-${record.id}").assertIsOff().assertIsEnabled()
            org.junit.Assert.assertFalse(record.id in service.catalog.selected(session))
        } finally {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, original)
            service.setEnabled(record.id, record.enabled)
            container.storage.deleteSessionPermanently(session)
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
