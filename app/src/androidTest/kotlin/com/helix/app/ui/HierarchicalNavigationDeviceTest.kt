package com.helix.app.ui

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HierarchicalNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun prepareShell() = compose.resetDeterministicUiState()

    @Test fun drawerRequiresExplicitOpenAndUsesTwoThirdsWidth() {
        compose.onNodeWithTag("chat-composer").performTouchInput { swipeRight() }
        compose.onNodeWithTag("drawer-new-conversation").assertIsNotDisplayed()
        compose.onNodeWithTag("open-navigation").performClick()
        compose.onNodeWithTag("drawer-new-conversation").assertIsDisplayed()
        val width =
            compose
                .onNodeWithTag("drawer-panel")
                .getUnclippedBoundsInRoot()
                .let { (it.right - it.left).value }
        val screen =
            compose
                .onRoot()
                .getUnclippedBoundsInRoot()
                .let { (it.right - it.left).value }
        org.junit.Assert.assertEquals(screen * 2f / 3f, width, 1f)
        compose.onNodeWithTag("drawer-search-conversations").assertDoesNotExist()
    }

    @Test fun providerBackReturnsThroughCategoryAndPreviousDestination() {
        compose.navigateTo("settings")
        compose.navigateTo("models")
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        compose.onNodeWithTag("provider-add").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()
    }

    @Test fun headerBackHonorsInnerPermissionPageBeforePoppingRoute() {
        compose.navigateTo("settings/permissions")
        compose.onNodeWithTag("settings-system-permissions").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-permissions").fetchSemanticsNodes().isNotEmpty() }
        if (compose.activity.packageName.endsWith(".developer")) {
            compose.onNodeWithTag("permission-files").performScrollTo().performClick()
            compose.onNodeWithTag("navigate-back").performClick()
            compose.onNodeWithTag("permission-files").performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithTag("permission-files").assertDoesNotExist()
        }
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("screen-settings-permissions").assertIsDisplayed()
    }

    @Test fun openDrawerConsumesBackWithoutClosingProviderCategory() {
        compose.navigateTo("models")
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        compose.onNodeWithTag("open-navigation").performClick()
        compose.waitUntil(10_000) { compose.onNodeWithTag("drawer-new-conversation").isDisplayed() }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onNodeWithTag("provider-add").isDisplayed() }
        compose.onNodeWithTag("provider-add").assertIsDisplayed()
    }

    @Test fun templateDialogBackKeepsProviderCategoryOpen() {
        compose.navigateTo("models")
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").performClick()
        compose.onNodeWithTag("provider-add").performClick()
        compose.onNodeWithTag("provider-template-picker").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("provider-template-picker").assertDoesNotExist()
        compose.onNodeWithTag("provider-add").assertIsDisplayed()
        compose.onNodeWithTag("provider-groups-back").performClick()
        compose.onNodeWithTag("provider-group-USER_CONFIGURED").assertIsDisplayed()
    }
}
