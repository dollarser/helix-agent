package com.helix.app.marketplace

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MarketplaceImportedItemUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Suppress("LongMethod") // Review/install/use through the real UI with only owned fixture data.
    fun importedCandidateRequiresConfirmationAndRoutesInstalledIdentity() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "market-ui-")
        val service =
            MarketplaceService(c.pluginService, c.skillRepository, MarketplaceSourceStore(root.resolve("sources")))
        val name = "market-ui-${UUID.randomUUID().toString().take(8)}"
        var used: String? = null
        var installedId: String? = null
        try {
            val archive = root.resolve("source.zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                val entries =
                    mapOf(
                        ".claude-plugin/marketplace.json" to
                            """{"name":"$name-market","plugins":[{"name":"$name","source":"./pkg"}]}""",
                        "pkg/.claude-plugin/plugin.json" to """{"name":"$name","version":"2.0"}""",
                        "pkg/skills/$name/SKILL.md" to "---\nname: $name\ndescription: Owned review\n---\nRead only",
                    )
                entries.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            service.imports.addSource(service.imports.previewSource(Uri.fromFile(archive.toFile())))
            val item = service.items().single { it.imported?.entry?.name == name }
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        MarketplaceSection(service, onUseRequested = { used = it })
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("marketplace-install-${item.id}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("marketplace-install-${item.id}").performScrollTo().performClick()
            compose.onNodeWithTag("marketplace-candidate-confirm").assertIsDisplayed()
            assertNull(service.findInstalled(item))
            compose.waitUntil(10_000) {
                !compose
                    .onNodeWithTag("marketplace-candidate-confirm")
                    .fetchSemanticsNode()
                    .config
                    .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            compose.onNodeWithTag("marketplace-candidate-confirm").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("marketplace-candidate-confirm").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("marketplace-expand-${item.id}").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("marketplace-use-${item.id}").fetchSemanticsNodes().isNotEmpty()
            }
            val installed = requireNotNull(service.findInstalled(item))
            installedId = installed.id
            assertEquals("2.0", installed.versionLabel)
            compose.onNodeWithTag("marketplace-use-${item.id}").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(installed.id, used) }
        } finally {
            // Even an assertion between commit and observation must remove only this owned installation.
            c.pluginService
                .list()
                .filter { it.name == name || it.id == installedId }
                .forEach(c.pluginService::remove)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
