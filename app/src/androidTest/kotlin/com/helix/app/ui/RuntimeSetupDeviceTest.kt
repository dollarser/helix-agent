package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RuntimeSetupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun standardExplainsRuntimeAvailabilityAndDoesNotStartInstallation() {
        compose.resetDeterministicUiState()
        val store = compose.container().profileStore
        store.switchTo(SafetyProfile.STANDARD)
        compose.navigateTo("setup/runtime")
        compose.onNodeWithTag("runtime-availability-notice").assertIsDisplayed()
        compose.onNodeWithTag("settings-proot-repair").assertDoesNotExist()
        if (compose.activity.packageName.endsWith(".developer")) {
            compose.onNodeWithTag("runtime-enable-advanced").performClick()
            assertEquals(SafetyProfile.STANDARD, store.profile)
        } else {
            compose.onNodeWithTag("runtime-enable-advanced").assertDoesNotExist()
        }
    }
}
