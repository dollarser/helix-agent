package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import org.junit.Rule
import org.junit.Test

/** Captures the production destinations after navigation, without creating or deleting user work. */
class UiExperienceCaptureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pluginAndWorkPagesHaveDistinctDestinations() {
        compose.navigateTo("extensions")
        compose.onNodeWithTag("extensions-tab-manage").assertIsDisplayed()
        captureUiExperience("plugins-installed")
        compose.onNodeWithTag("extensions-tab-market").performScrollTo().performClick()
        compose.waitForIdle()
        captureUiExperience("plugins-market")
        compose.onNodeWithTag("extensions-add").performScrollTo().performClick()
        compose.waitForIdle()
        captureUiExperience("plugins-import")
        compose.navigateTo("tasks")
        compose.onNodeWithTag("screen-tasks").assertIsDisplayed()
        captureUiExperience("work-tasks")
        compose.navigateTo("artifacts")
        compose.onNodeWithTag("screen-artifacts").assertIsDisplayed()
        captureUiExperience("work-artifacts")
    }
}
