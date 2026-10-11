package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.connector.PluginSessionChoice
import com.helix.app.plugin.PluginSessionRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure display fixtures; never modifies the daily database or system grants. */
class PluginSessionChoiceDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val row = PluginSessionRow("bundled-plugin:mobile-use", "Mobile Use", false, false, true, true)
    private val tag = "connector-session-${row.id}"

    @Test fun missingConfigurationClickOpensSetupWithoutSelecting() = blocked("MOBILE_USE_NOT_CONFIGURED")

    @Test fun missingBackendClickOpensSetupWithoutSelecting() = blocked("MOBILE_USE_BACKEND_UNAVAILABLE")

    private fun blocked(error: String) {
        var configured = 0
        val selections = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                PluginSessionChoice(
                    row.copy(selectionError = error),
                    false,
                    { selections += it },
                    { configured++ },
                )
            }
        }
        compose
            .onNodeWithTag(tag)
            .assertIsOff()
            .performClick()
            .assertIsOff()
        assertEquals(1, configured)
        assertEquals(emptyList<Boolean>(), selections)
    }

    @Test fun returningFromSetupRequiresAnotherExplicitClick() {
        val facts = mutableStateOf(row.copy(selectionError = "MOBILE_USE_BACKEND_UNAVAILABLE"))
        val selections = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme { PluginSessionChoice(facts.value, false, { selections += it }, {}) }
        }
        compose.onNodeWithTag(tag).performClick().assertIsOff()
        compose.runOnIdle { facts.value = row }
        compose.onNodeWithTag(tag).assertIsOff()
        assertEquals(emptyList<Boolean>(), selections)
        compose.onNodeWithTag(tag).performClick()
        assertEquals(listOf(true), selections)
    }

    @Test fun disconnectedSelectedPluginCanStillBeDeselected() {
        val selections = mutableListOf<Boolean>()
        var configured = 0
        compose.setContent {
            MaterialTheme {
                PluginSessionChoice(
                    row.copy(selected = true, available = false, selectionError = "MOBILE_USE_BACKEND_UNAVAILABLE"),
                    false,
                    { selections += it },
                    { configured++ },
                )
            }
        }
        compose.onNodeWithTag(tag).assertIsOn().performClick()
        assertEquals(listOf(false), selections)
        assertEquals(0, configured)
    }

    @Test fun busySelectionDoesNotOpenSetupOrWrite() {
        var actions = 0
        compose.setContent {
            MaterialTheme {
                PluginSessionChoice(
                    row.copy(selectionError = "MOBILE_USE_BACKEND_UNAVAILABLE"),
                    true,
                    { actions++ },
                    { actions++ },
                )
            }
        }
        compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onNodeWithTag("plugin-session-configure-${row.id}").assertIsNotEnabled()
        assertEquals(0, actions)
    }
}
