package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.helix.app.MainActivity
import com.helix.app.proot.ProotToolModule
import org.junit.Rule
import org.junit.Test

class RuntimeSetupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun runtimeAvailabilityComesFromChannelWithoutModeSelectionOrInstallation() {
        compose.resetDeterministicUiState()
        compose.navigateTo("setup/runtime")
        compose.onNodeWithTag("runtime-enable-advanced").assertDoesNotExist()
        compose.onNodeWithTag("settings-risk-dialog").assertDoesNotExist()
        if (ProotToolModule.AVAILABLE) {
            compose.onNodeWithTag("settings-proot-manage").assertExists()
            compose.onNodeWithTag("runtime-availability-notice").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("runtime-availability-notice").assertExists()
            compose.onNodeWithTag("settings-proot-manage").assertDoesNotExist()
        }
    }
}
