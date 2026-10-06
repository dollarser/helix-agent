package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReasoningDetectionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownCapabilitiesAreDetectedOnlyOnClickThenOfferReturnedOptions() {
        var probes = 0
        var selected = ReasoningEffort.OFF
        compose.setContent {
            MaterialTheme {
                ComposerReasoningMenu(
                    selected,
                    true,
                    { selected = it },
                    efforts = emptyList(),
                    onDetect = {
                        probes++
                        listOf(ReasoningEffort.OFF, ReasoningEffort.HIGH)
                    },
                )
            }
        }
        compose.runOnIdle { assertEquals(0, probes) }
        compose.onNodeWithTag("chat-reasoning-menu").assertIsEnabled().performClick()
        compose.onNodeWithTag("chat-reasoning-high").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(ReasoningEffort.HIGH, selected)
            assertEquals(1, probes)
        }
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-low").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, probes) }
    }

    @Test fun failedDetectionDoesNotOfferInventedLevels() {
        compose.setContent {
            MaterialTheme {
                ComposerReasoningMenu(
                    ReasoningEffort.OFF,
                    true,
                    {},
                    efforts = emptyList(),
                    onDetect = { error("offline") },
                )
            }
        }
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-status").assertIsDisplayed()
        compose.onNodeWithTag("chat-reasoning-high").assertDoesNotExist()
    }
}
