package com.helix.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.ui.BrowserScreen
import org.junit.Rule
import org.junit.Test

class BrowserRedesignUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun downloadsMenuOpensTheActualEmptyList() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var controller: BrowserController
        compose.runOnUiThread { controller = BrowserController(context) }
        compose.setContent { MaterialTheme { BrowserScreen(controller) } }
        compose.onNodeWithTag("browser-menu").performClick()
        compose
            .onNodeWithText(
                context.getString(com.helix.feature.browser.R.string.browser_menu_downloads),
            ).performClick()
        compose
            .onNodeWithText(context.getString(com.helix.feature.browser.R.string.browser_downloads_empty))
            .assertIsDisplayed()
    }

    @Test
    fun fullTabSwitcherDisablesNormalAndNoHistoryCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var controller: BrowserController
        compose.runOnUiThread {
            controller = BrowserController(context)
            repeat(8) { controller.newTab() }
        }
        compose.setContent { MaterialTheme { BrowserScreen(controller) } }
        compose.onNodeWithTag("browser-tabs").performClick()
        compose.onNodeWithTag("browser-tab-new").assertIsNotEnabled()
        compose.onNodeWithTag("browser-tab-new-no-history").assertIsNotEnabled()
        compose
            .onNodeWithText(
                context.getString(com.helix.feature.browser.R.string.browser_tab_incognito),
                substring = true,
            ).assertIsDisplayed()
    }
}
