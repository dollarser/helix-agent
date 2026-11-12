package com.helix.app.connector

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ConnectorSessionPanelDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sessionSelectionDoesNotEditGlobalNewSessionDefaults() {
        verifySelection(scoped = false)
    }

    @Test
    fun scopedSelectionHidesOtherPluginsAndPreservesTheirSelection() {
        verifySelection(scoped = true)
    }

    private fun verifySelection(scoped: Boolean) {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val service = c.pluginService
        val session = "connector-ui-${UUID.randomUUID()}"
        val next = "$session-next"
        val record = service.install(selectionPackage(session), identity = session)
        c.storage.sessions.create(session, "UI", null, null, 0)
        val initiallySelected = service.catalog.selected(session)
        val revision = androidx.compose.runtime.mutableIntStateOf(0)
        try {
            assertEquals(1, service.sessionRows(session).single { it.id == record.id }.readyComponents)
            compose.setContent { SelectionPanel(service, session, record.id.takeIf { scoped }, revision) }
            val selected = "connector-session-${record.id}"
            val default = "connector-default-${record.id}"
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(selected).fetchSemanticsNodes().isNotEmpty() }
            if (!scoped) savePickerScreenshot()
            if (scoped) {
                service.sessionRows(session).filter { it.id != record.id }.forEach {
                    compose.onNodeWithTag("connector-session-${it.id}").assertDoesNotExist()
                }
            }
            compose
                .onNodeWithTag(selected)
                .performScrollTo()
                .assertIsOff()
                .performClick()
            waitSelection(selected, true)
            compose.onNodeWithTag("plugin-session-apply").performClick()
            compose.waitUntil(10_000) { record.id in service.catalog.selected(session) }
            waitSelection(selected, true)
            compose
                .onNodeWithTag(selected)
                .performScrollTo()
                .assertIsEnabled()
                .assertIsOn()
            compose.onNodeWithTag(default).assertDoesNotExist()
            service.catalog.setDefault(record.id, true)
            c.storage.sessions.create(next, "New", null, null, 0)
            compose.onNodeWithTag(selected).performScrollTo().performClick()
            waitSelection(selected, false)
            compose.onNodeWithTag("plugin-session-apply").performClick()
            compose.waitUntil(10_000) { service.catalog.selected(session) == initiallySelected }
            assertEquals(initiallySelected + record.id, service.catalog.selected(next))
            assertTrue(service.catalog.defaultSelected(record.id))
        } finally {
            service.remove(record)
            c.storage.deleteSessionPermanently(session)
            if (c.storage.sessions
                    .list()
                    .any { it.id == next }
            ) {
                c.storage.deleteSessionPermanently(next)
            }
        }
    }

    private fun savePickerScreenshot() {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        // The Compose clock does not advance the platform window's enter animation.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val context = ApplicationProvider.getApplicationContext<HelixApplication>()
        File(context.cacheDir, "plugin-selection-compact.png").outputStream().use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    private fun waitSelection(
        tag: String,
        selected: Boolean,
    ) {
        compose.waitUntil(10_000) {
            runCatching {
                val node = compose.onNodeWithTag(tag).assertIsEnabled()
                if (selected) node.assertIsOn() else node.assertIsOff()
            }.isSuccess
        }
    }

    @androidx.compose.runtime.Composable
    @Suppress("FunctionName")
    private fun SelectionPanel(
        service: com.helix.app.plugin.PluginService,
        session: String,
        pluginId: String?,
        revision: androidx.compose.runtime.MutableIntState,
    ) {
        MaterialTheme {
            androidx.compose.runtime.key(revision.intValue) {
                ConnectorSessionPanel(
                    service,
                    session,
                    { _, _ -> },
                    pluginId = pluginId,
                    onDismiss = { revision.intValue++ },
                )
            }
        }
    }

    private fun selectionPackage(name: String) =
        PluginPackageReader().parse(
            mapOf(
                ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                ".mcp.json" to """{"mcpServers":{"fixture":{"url":"https://example.invalid/ui"}}}""".toByteArray(),
                "skills/$name/SKILL.md" to
                    "---\nname: $name\ndescription: Selection fixture\n---\nDraft only".toByteArray(),
            ),
        )
}
