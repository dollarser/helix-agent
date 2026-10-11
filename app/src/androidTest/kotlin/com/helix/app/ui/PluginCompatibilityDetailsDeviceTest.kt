package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.app.plugin.PluginCompatibilityDetails
import com.helix.extensions.plugin.PluginDiagnostics
import org.junit.Rule
import org.junit.Test

class PluginCompatibilityDetailsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun supportLimitsAreCollapsedAndExpansionDoesNotChangeSelection() {
        val facts = listOf(PluginDiagnostics.project("UNSUPPORTED_LSPSERVERS"))
        compose.setContent { MaterialTheme { Column { PluginCompatibilityDetails(facts) } } }
        compose.onNodeWithText("lspServers", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("plugin-compatibility-toggle").performClick()
        compose.onNodeWithText("lspServers", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("plugin-compatibility-toggle").performClick()
        compose.onNodeWithText("lspServers", substring = true).assertDoesNotExist()
    }

    @Test fun replacingDiagnosticsCollapsesTheOldPackageDetails() {
        val facts = mutableStateOf(listOf(PluginDiagnostics.project("UNSUPPORTED_HOOKS")))
        compose.setContent { MaterialTheme { Column { PluginCompatibilityDetails(facts.value) } } }
        compose.onNodeWithTag("plugin-compatibility-toggle").performClick()
        compose.onNodeWithText("hooks", substring = true).assertIsDisplayed()
        compose.runOnIdle { facts.value = listOf(PluginDiagnostics.project("UNSUPPORTED_AGENTS")) }
        compose.onNodeWithText("hooks", substring = true).assertDoesNotExist()
        compose.onNodeWithText("agents", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("plugin-compatibility-toggle").performClick()
        compose.onNodeWithText("agents", substring = true).assertIsDisplayed()
    }
}
