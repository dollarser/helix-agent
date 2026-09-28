package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.localmodel.LocalModelDownloader
import com.helix.provider.api.local.ModelAssetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class LocalModelStorageDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cleanupRequiresConfirmationAndRefreshesRealFilesAtLargeFont() {
        val root =
            Files
                .createTempDirectory(
                    InstrumentationRegistry
                        .getInstrumentation()
                        .targetContext.cacheDir
                        .toPath(),
                    "model-storage-test",
                ).toFile()
        try {
            val transfers = root.resolve("transfers")
            val downloader =
                LocalModelDownloader(ModelAssetStore(root.resolve("models")), transfers, { 0L }) {
                    error("No network expected")
                }
            val partial = transfers.resolve("${"a".repeat(64)}.part").apply { writeText("partial") }
            val preserved = transfers.resolve("unknown.txt").apply { writeText("keep") }
            var attempts = 0
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            LocalModelStorageSection(false, downloader::storageSnapshot) {
                                attempts++
                                downloader.clearDownloads(it)
                            }
                        }
                    }
                }
            }
            awaitTag("local-model-storage-clear")
            compose
                .onNodeWithTag("local-model-storage-clear")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("local-model-storage-cancel").assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertTrue(partial.exists())
                assertEquals(0, attempts)
            }
            compose.onNodeWithTag("local-model-storage-clear").performClick()
            compose.onNodeWithTag("local-model-storage-confirm").assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { !partial.exists() }
            awaitTag("local-model-storage-usage")
            compose.runOnIdle {
                assertFalse(partial.exists())
                assertEquals(1, attempts)
                assertEquals("keep", preserved.readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
