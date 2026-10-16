package com.helix.app.plugin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.connector.ConnectorSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Owned local import/Room/UI only; never connects to the declared example endpoints. */
class PluginImportDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val service get() = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.pluginService

    @Test fun failedPreviewCanRecoverAndResultActionsKeepExactIdentity() {
        val name = "import-${UUID.randomUUID().toString().take(8)}"
        val json = config(name, "first")
        val bundle = service.previewJson(json)
        var used: String? = null
        var configured: String? = null
        try {
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ConnectorSection(service, showInstalled = false, onUse = { used = it }, onConfigureImported = {
                            configured =
                                it
                        })
                    }
                }
            }
            click("connector-paste")
            preview("not JSON")
            waitFor("plugin-import-error")
            compose.onNodeWithTag("connector-install").assertDoesNotExist()
            preview(json)
            waitFor("connector-install")
            compose.onNodeWithTag("plugin-import-error").assertDoesNotExist()
            compose.onNodeWithText(bundle.contentHash, substring = true).assertDoesNotExist()
            click("connector-install")
            waitFor("plugin-import-done")
            val record = service.list().single { it.hash == bundle.contentHash }
            assertFalse(service.catalog.defaultSelected(record.id))
            click("plugin-import-configure")
            assertEquals(record.id, configured)
            click("plugin-import-use")
            assertEquals(record.id, used)
        } finally {
            service.list().filter { it.hash == bundle.contentHash }.forEach(service::remove)
        }
    }

    @Test fun importOfChangedLocalPackageRequiresTargetAndPreservesInstallationIdentity() {
        val name = "update-${UUID.randomUUID().toString().take(8)}"
        val old = service.install(service.previewJson(config(name, "old")))
        var used: String? = null
        try {
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ConnectorSection(service, showInstalled = false, onUse = { used = it })
                    }
                }
            }
            click("connector-paste")
            preview(config(name, "new"))
            waitFor("connector-install")
            compose.onNodeWithTag("connector-install").assertIsNotEnabled()
            assertEquals(old.hash, service.list().single { it.id == old.id }.hash)
            assertNull(used)
            click("plugin-import-target-${old.id}")
            click("connector-install")
            waitFor("plugin-import-done")
            val updated = service.list().single { it.id == old.id }
            assertEquals(old.identity, updated.identity)
            assertEquals(old.revision + 1, updated.revision)
            click("plugin-import-use")
            assertEquals(old.id, used)
        } finally {
            service.list().filter { it.id == old.id }.forEach(service::remove)
        }
    }

    @Test fun capturedUpdateRejectsConcurrentChangeAndCancellationPreservesOldRecord() {
        val name = "revision-${UUID.randomUUID().toString().take(8)}"
        val original = service.install(service.previewJson(config(name, "old")))
        try {
            val next = service.previewJson(config(name, "new"))
            val review = PluginImportReview.capture(next, service.list(), original)
            assertThrows(IllegalStateException::class.java) {
                service.install(next, review.identity, review.target?.revision, cancelled = { true })
            }
            assertEquals(original, service.list().single { it.id == original.id })
            val intermediate =
                service.install(
                    service.previewJson(config(name, "intermediate")),
                    original.identity,
                    original.revision,
                )
            assertThrows(IllegalArgumentException::class.java) {
                service.install(next, review.identity, review.target?.revision)
            }
            assertEquals(intermediate, service.list().single { it.id == original.id })
        } finally {
            service.list().filter { it.id == original.id }.forEach(service::remove)
        }
    }

    private fun config(
        name: String,
        path: String,
    ) = """{"mcpServers":{"$name":{"url":"https://example.com/$name/$path"}}}"""

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun preview(json: String) {
        compose.onNodeWithTag("connector-json").performScrollTo().performTextReplacement(json)
        click("connector-json-preview")
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
