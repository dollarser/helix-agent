package com.helix.app.eval

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.chat.ArtifactQuery
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.ui.ArtifactAvailability
import com.helix.app.ui.ArtifactFileDialog
import com.helix.app.ui.LocalizedComposeActivity
import com.helix.app.ui.inspectArtifactAvailability
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Preview the actual artifact from the explicitly selected synthetic model run. No external sharing. */
class SyntheticDeliveryPreviewDeviceTest {
    private var row by mutableStateOf<ArtifactRowUi?>(null)

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                MaterialTheme {
                    row?.let {
                        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
                        ArtifactFileDialog(c.fileManager, it, onOpenSession = {}, onDismiss = {})
                    }
                }
            }
        }

    @After fun clearContent() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun producedArtifactIsHashCheckedAndActuallyRendered() =
        runBlocking {
            val session = InstrumentationRegistry.getArguments().getString("helixSyntheticSession")
            org.junit.Assume.assumeTrue("Explicit synthetic session required", session != null)
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            check(
                c.storage.sessions
                    .resolve(requireNotNull(session))
                    .title
                    .startsWith("Synthetic closed loop scripts-"),
            )
            val artifact =
                ArtifactQuery(c.storage).forSession(session).single {
                    it.relativePath.endsWith("output/skill.txt")
                }
            val inspected = inspectArtifactAvailability(c.fileManager, artifact)
            assertTrue(inspected is ArtifactAvailability.Ready)
            inspected as ArtifactAvailability.Ready
            assertFalse(inspected.changed)
            assertNotNull(inspected.meta.sha256)
            assertTrue(inspected.text?.startsWith("owned-") == true)
            compose.runOnIdle {
                row = artifact
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(requireNotNull(inspected.text)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(requireNotNull(inspected.text)).assertIsDisplayed()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            requireNotNull(bitmap)
            File(app.filesDir, "closed-loop-eval/delivery-preview.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
}
