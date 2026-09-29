package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import com.helix.app.MainActivity
import org.junit.Rule
import org.junit.Test

class HierarchicalNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun providerBackReturnsThroughCategoryAndPreviousDestination() {
        compose.navigateTo("settings")
        compose.navigateTo("models")
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        compose.onNodeWithTag("provider-add").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()
    }

    @Test fun headerBackHonorsInnerPermissionPageBeforePoppingRoute() {
        compose.navigateTo("settings/permissions")
        compose.onNodeWithTag("settings-system-permissions").performScrollTo().performClick()
        compose.onNodeWithTag("permission-files").performScrollTo().performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("permission-files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("screen-settings-permissions").assertIsDisplayed()
    }

    @Test fun openDrawerConsumesBackWithoutClosingProviderCategory() {
        compose.navigateTo("models")
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        compose.onNodeWithTag("open-navigation").performClick()
        Espresso.pressBack()
        compose.onNodeWithTag("provider-add").assertIsDisplayed()
    }

    @Test fun templateDialogBackKeepsProviderCategoryOpen() {
        compose.navigateTo("models")
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        compose.onNodeWithTag("provider-add").performClick()
        compose.onNodeWithTag("provider-template-picker").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("provider-template-picker").assertDoesNotExist()
        compose.onNodeWithTag("provider-add").assertIsDisplayed()
        compose.onNodeWithTag("provider-groups-back").performClick()
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").assertIsDisplayed()
    }
}
