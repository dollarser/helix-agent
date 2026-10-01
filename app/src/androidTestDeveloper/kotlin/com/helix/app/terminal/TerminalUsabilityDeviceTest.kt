package com.helix.app.terminal

import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI/font tests without starting a shell; real IME/PTY journey remains in ProotTerminalUiDeviceTest. */
class TerminalUsabilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun additionalKeysAreCollapsedInitiallyAndCannotWriteFromReadOnlyConnection() {
        compose.setContent { MaterialTheme { TerminalExtraKeys(false) { error("read-only input") } } }
        compose.onNodeWithTag("terminal-key-3").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-key-home").assertDoesNotExist()
        compose.onNodeWithTag("terminal-more-keys").performClick()
        compose.onNodeWithTag("terminal-key-home").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("terminal-more-keys").performClick()
        compose.onNodeWithTag("terminal-key-home").assertDoesNotExist()
    }

    @Test fun bundledFontIsMonospacedAndAttributionIsAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paint =
            Paint().apply {
                typeface = context.resources.getFont(R.font.inconsolata_regular)
                textSize = 20f
            }
        assertEquals(paint.measureText("iiii"), paint.measureText("MMMM"), 0.01f)
        assertTrue(paint.measureText("M") in 8f..11f)
        for (file in listOf("Inconsolata-NOTICE.txt", "Inconsolata-OFL.txt")) {
            context.assets.open("terminal-licenses/$file").use { assertTrue(it.read() != -1) }
        }
    }
}
