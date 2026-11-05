package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.connector.PluginSessionChoice
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure display fixtures; never modifies the daily database or system grants. */
class PluginSessionChoiceDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val row =
        PluginSessionRow(
            "bundled-plugin:mobile-use",
            "Mobile Use",
            false,
            false,
            true,
            true,
            readyComponents = 1,
            totalComponents = 1,
        )
    private val tag = "connector-session-${row.id}"

    @Test fun unreadableConfigurationOffersRepairWithoutSelecting() = blocked(PluginSetupReason.MOBILE_CONFIGURATION)

    @Test fun missingBackendOffersExplicitSetupWithoutSelecting() = blocked(PluginSetupReason.MOBILE_BACKEND)

    private fun blocked(error: PluginSetupReason) {
        var configured = 0
        val selections = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                PluginSessionChoice(
                    row.copy(ready = false, readyComponents = 0, setupReason = error),
                    false,
                    { selections += it },
                    { configured++ },
                )
            }
        }
        compose
            .onNodeWithTag(tag)
            .assertIsOff()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(true), selections)
        assertEquals(0, configured)
        compose.onNodeWithTag("plugin-session-configure-${row.id}").assertDoesNotExist()
        compose.onNodeWithTag("plugin-session-issue-${row.id}", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(0, configured)
        assertEquals(listOf(true), selections)
    }

    @Test fun readinessChangeWithoutPendingSelectionDoesNotSelect() {
        val facts =
            mutableStateOf(row.copy(ready = false, readyComponents = 0, setupReason = PluginSetupReason.MOBILE_BACKEND))
        val selections = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme { PluginSessionChoice(facts.value, false, { selections += it }, {}) }
        }
        compose.onNodeWithTag(tag).assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("plugin-session-configure-${row.id}").assertDoesNotExist()
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
                    row.copy(selected = true, available = false, setupReason = PluginSetupReason.MOBILE_BACKEND),
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
                    row.copy(setupReason = PluginSetupReason.MOBILE_BACKEND),
                    true,
                    { actions++ },
                    { actions++ },
                )
            }
        }
        compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onNodeWithTag("plugin-session-configure-${row.id}").assertDoesNotExist()
        assertEquals(0, actions)
    }

    @Test fun routineAndPartialReadinessDoNotAddStatusRowsOrChangeHeight() {
        val facts = mutableStateOf(row)
        val context = ApplicationProvider.getApplicationContext<HelixApplication>()
        compose.setContent { MaterialTheme { PluginSessionChoice(facts.value, false, {}, {}) } }
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(context.getString(R.string.plugin_component_ready)).assertDoesNotExist()
        compose.runOnIdle { facts.value = row.copy(ready = false, totalComponents = 3, hasSkippedComponents = true) }
        compose.onNodeWithText(context.getString(R.string.plugin_skipped_components)).assertDoesNotExist()
        compose.onNodeWithTag("plugin-session-issue-${row.id}", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            facts.value =
                row.copy(
                    selected = true,
                    ready = false,
                    readyComponents = 0,
                    setupReason = PluginSetupReason.HOST_UNAVAILABLE,
                )
        }
        compose.onNodeWithTag(tag).assertIsOn()
        compose.onNodeWithTag("plugin-session-configure-${row.id}").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.connector_session_setup)).assertDoesNotExist()
        compose.onNodeWithTag("plugin-session-issue-${row.id}", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun selectedCapabilityLosingPermissionKeepsAnExplicitRepairAction() {
        var repairs = 0
        val choices = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                PluginSessionChoice(
                    row.copy(
                        selected = true,
                        ready = false,
                        readyComponents = 0,
                        setupReason = PluginSetupReason.MOBILE_BACKEND,
                    ),
                    false,
                    { choices += it },
                    { repairs++ },
                )
            }
        }
        compose.onNodeWithTag("plugin-session-issue-${row.id}", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("plugin-session-configure-${row.id}").performClick()
        compose.onNodeWithTag(tag).assertIsOn()
        assertEquals(1, repairs)
        assertEquals(emptyList<Boolean>(), choices)
    }
}
