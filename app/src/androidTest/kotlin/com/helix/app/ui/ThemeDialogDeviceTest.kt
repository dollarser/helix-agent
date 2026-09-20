package com.helix.app.ui

import android.content.res.Configuration
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Opens production dialogs from the file page; no synthetic theme or preview content. */
class ThemeDialogDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realFileDialogsRenderInSystemThemeAndCanBeDismissed() {
        val night =
            compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        InstrumentationRegistry.getArguments().getString("expectedNight")?.let {
            require(it == "yes" || it == "no")
            assertEquals(it == "yes", night)
        }
        compose.resetDeterministicUiState()
        compose.navigateTo("files")
        for ((open, dialog, close) in listOf(
            Triple("files-saf-open", "files-saf-dialog", "files-saf-close"),
            Triple("files-newfolder", "files-newfolder-dialog", "files-newfolder-cancel"),
        )) {
            if (open == "files-newfolder") openDirectoryControls()
            compose.onNodeWithTag(open).performScrollTo().performClick()
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag(dialog).captureToImage().toPixelMap()
            val luminance = mutableListOf<Float>()
            for (y in 0 until pixels.height step 4) {
                for (x in 0 until pixels.width step 4) {
                    val color = pixels[x, y]
                    luminance.add((color.red + color.green + color.blue) / 3)
                }
            }
            luminance.sort()
            val median = luminance[luminance.size / 2]
            assertTrue("$dialog night=$night median=$median", if (night) median < 0.4 else median > 0.6)
            compose.onNodeWithTag(close).performClick()
            compose.onNodeWithTag(dialog).assertDoesNotExist()
        }
    }

    private fun openDirectoryControls() {
        compose.onNodeWithTag("files-quick-work").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("files-controls-open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("files-controls-open").performClick()
    }
}
