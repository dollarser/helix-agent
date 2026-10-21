package com.helix.app

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The loading frame must follow system mode before any service-backed page. */
class StartupThemeDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingFrameUsesDarkSurfaceInNightMode() = verify(night = true)

    @Test fun loadingFrameUsesLightSurfaceInDayMode() = verify(night = false)

    private fun verify(night: Boolean) {
        val configuration =
            Configuration(
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext.resources.configuration,
            )
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                AppStartupScreen(AppStartupState.Loading, {}, {})
            }
        }
        val background =
            compose
                .onNodeWithTag("app-startup")
                .captureToImage()
                .toPixelMap()[1, 1]
                .luminance()
        assertTrue(
            "Loading background luminance=$background night=$night",
            if (night) {
                background < 0.3f
            } else {
                background >
                    0.7f
            },
        )
    }
}
