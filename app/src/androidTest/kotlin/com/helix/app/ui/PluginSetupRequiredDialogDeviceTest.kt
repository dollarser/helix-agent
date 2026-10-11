package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.plugin.PluginSetupReason
import com.helix.app.plugin.PluginSetupRequiredDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginSetupRequiredDialogDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var navigations = 0
    private var dismissals = 0

    private fun show(
        error: PluginSetupReason,
        explanation: Int,
    ) {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    PluginSetupRequiredDialog(
                        error,
                        onContinue = {
                            navigations++
                            visible.value = false
                        },
                        onDismiss = {
                            dismissals++
                            visible.value = false
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, navigations)
        compose.onNodeWithTag("plugin-setup-explanation").assertTextEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(explanation),
        )
    }

    @Test fun missingBackendDoesNotNavigateUntilExplicitConfirmation() {
        show(PluginSetupReason.MOBILE_BACKEND, R.string.plugin_setup_mobile_backend)
        compose.onNodeWithTag("plugin-setup-continue").performClick()
        compose.waitForIdle()
        assertEquals(1, navigations)
        assertEquals(0, dismissals)
    }

    @Test fun decliningScopeSetupDoesNotNavigate() {
        show(PluginSetupReason.MOBILE_SCOPE, R.string.plugin_setup_mobile_scope)
        compose.onNodeWithTag("plugin-setup-dismiss").performClick()
        compose.waitForIdle()
        assertEquals(0, navigations)
        assertEquals(1, dismissals)
    }

    @Test fun unknownRequirementAlsoWaitsForConfirmation() {
        show(PluginSetupReason.CONTENT, R.string.plugin_setup_generic)
        compose.onNodeWithTag("plugin-setup-continue").performClick()
        compose.waitForIdle()
        assertEquals(1, navigations)
    }
}
