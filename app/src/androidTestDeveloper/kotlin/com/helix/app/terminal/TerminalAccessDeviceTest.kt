package com.helix.app.terminal

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.app.HelixTheme
import com.helix.app.profile.SafetyProfileStore
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Uses an isolated profile store and callback; never starts a shell or changes the daily profile. */
class TerminalAccessDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun standardModeRequiresExplicitConfirmationAndCancelLeavesItUntouched() {
        val store =
            object : SafetyProfileStore {
                override val flow = MutableStateFlow(SafetyProfile.STANDARD)
                override val profile: SafetyProfile get() = flow.value

                override fun switchTo(profile: SafetyProfile) {
                    flow.value = profile
                }
            }
        var starts = 0
        compose.setContent {
            HelixTheme {
                ManualTerminalAccess(store, { starts++ }, {}, {}) { Text("terminal content") }
            }
        }
        compose.onNodeWithTag("terminal-advanced-required").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("terminal-switch-advanced").performClick()
        compose.onNodeWithTag("settings-risk-cancel").performClick()
        compose.runOnIdle {
            assertEquals(SafetyProfile.STANDARD, store.profile)
            assertEquals(0, starts)
        }
        compose.onNodeWithTag("terminal-switch-advanced").performClick()
        compose.onNodeWithTag("settings-risk-confirm").performClick()
        compose.onNodeWithText("terminal content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
