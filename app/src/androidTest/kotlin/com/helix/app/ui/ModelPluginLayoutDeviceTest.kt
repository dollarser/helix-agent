package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import com.helix.app.R
import org.junit.Rule
import org.junit.Test

/** Read-only production page acceptance, including optional screenshots for bounded layout runs. */
class ModelPluginLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sourcesAndPluginDetailsRemainAccessibleWithoutChangingConfiguration() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("open-navigation").fetchSemanticsNodes().isNotEmpty()
        }
        compose.navigateTo("models")
        compose.onNodeWithTag("provider-group-BUNDLED_SERVICE").assertDoesNotExist()
        compose
            .onNodeWithTag("provider-group-USER_CONFIGURED")
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("provider-add").performScrollTo().assertIsDisplayed()
        captureChatLayout(compose.activity, "hxa307-models-api")
        if (com.helix.app.profile.AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            compose
                .onNodeWithTag("provider-group-MANAGED_ACCOUNT")
                .assertIsDisplayed()
                .performClick()
                .assertIsSelected()
            captureChatLayout(compose.activity, "hxa307-models-subscription")
        }
        compose
            .onNodeWithTag("provider-group-ON_DEVICE_ASSET")
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        captureChatLayout(compose.activity, "hxa307-models-local")
        compose.navigateTo("extensions")
        compose.onNodeWithTag("extensions-tab-manage").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plugin-manage-bundled-plugin:browser").performScrollTo().performClick()
        // Bring the horizontal tab row into the outer vertical viewport before scrolling its tabs.
        compose.onNodeWithTag("plugin-tab-2-bundled-plugin:browser").onParent().performScrollTo()
        compose
            .onNodeWithTag("plugin-tab-2-bundled-plugin:browser")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose
            .onNodeWithText(compose.activity.getString(R.string.plugin_version, "1"))
            .performScrollTo()
            .assertIsDisplayed()
        captureChatLayout(compose.activity, "hxa307-plugin-details")
        compose.onNodeWithTag("plugin-tab-0-bundled-plugin:browser").onParent().performScrollTo()
        compose.onNodeWithTag("plugin-tab-0-bundled-plugin:browser").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-readiness-bundled-plugin:browser").performScrollTo().performClick()
        val revisionLabel = compose.activity.getString(R.string.plugin_revision, 999).substringBefore("999")
        compose.onNodeWithText(revisionLabel, substring = true).assertDoesNotExist()
    }
}
