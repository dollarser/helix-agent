package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.app.HelixTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Stateless presentation only; no settings save, OS grant, account or audio request. */
class SpeechSettingsPresentationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labelledRowTogglesOnlyTheLocalDraft() {
        var checked by mutableStateOf(false)
        var changes = 0
        compose.setContent {
            HelixTheme {
                SpeechSettingToggle("Continuous speech", checked, {
                    checked = it
                    changes++
                })
            }
        }
        compose
            .onNodeWithText("Continuous speech")
            .assertIsOff()
            .performClick()
            .assertIsOn()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test fun mandatoryAccountSwitchCannotBeDisabledThroughItsLabel() {
        var changes = 0
        compose.setContent { HelixTheme { SpeechSettingToggle("Account login", true, { changes++ }, enabled = false) } }
        compose.onNodeWithText("Account login").assertIsNotEnabled().assertIsOn()
        compose.runOnIdle { assertEquals(0, changes) }
    }
}
