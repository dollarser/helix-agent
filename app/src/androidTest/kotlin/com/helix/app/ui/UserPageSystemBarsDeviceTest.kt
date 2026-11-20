package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real window screenshots catch system-bar scrims that Compose-only captures exclude. */
class UserPageSystemBarsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightPagePaintsSystemBarsAndKeepsHeaderBelowStatusBar() = verify(light = true)

    @Test fun darkPagePaintsSystemBarsAndKeepsHeaderBelowStatusBar() = verify(light = false)

    private fun verify(light: Boolean) {
        val background = if (light) Color(0xFFF8F8F8) else Color(0xFF202020)
        var dialogView: android.view.View? = null
        compose.setContent {
            MaterialTheme(
                colorScheme =
                    if (light) {
                        lightColorScheme(
                            background = background,
                        )
                    } else {
                        darkColorScheme(background = background)
                    },
            ) {
                UserPageFrame("Fixture", "system-bars-page", {}) {
                    val view = LocalView.current
                    SideEffect { dialogView = view }
                    Text("Page content")
                }
            }
        }
        compose.waitForIdle()
        var statusHeight = 0
        compose.runOnIdle {
            val view = checkNotNull(dialogView)
            val window = (view.parent as DialogWindowProvider).window
            statusHeight =
                checkNotNull(ViewCompat.getRootWindowInsets(view))
                    .getInsets(WindowInsetsCompat.Type.statusBars())
                    .top
            assertTrue(statusHeight > 0)
            assertEquals(light, WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars)
            assertEquals(light, WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars)
        }
        val bounds = compose.onNodeWithTag("system-bars-page-back").fetchSemanticsNode().boundsInWindow
        assertTrue("Header must not overlap status bar", bounds.top >= statusHeight)
        // Compose idleness does not include the platform Dialog entrance animation.
        compose.waitUntil(timeoutMillis = 5_000) {
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                screenshot.getPixel(screenshot.width / 2, statusHeight / 2) == background.toArgb()
            } finally {
                screenshot.recycle()
            }
        }
    }
}
