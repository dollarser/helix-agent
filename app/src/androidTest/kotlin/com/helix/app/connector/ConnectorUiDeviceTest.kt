package com.helix.app.connector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.app.ui.dismissFirstLaunchIfNeeded
import com.helix.app.ui.navigateTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectorUiDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsConnectorImportEntrySurvivesActivityRecreation() {
        composeRule.dismissFirstLaunchIfNeeded()
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("connector-import").performScrollTo().assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("connector-import").performScrollTo().assertIsDisplayed()
    }
}
