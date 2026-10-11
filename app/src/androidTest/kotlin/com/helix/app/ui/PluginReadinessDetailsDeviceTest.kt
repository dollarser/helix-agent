package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.app.plugin.PluginComponentFact
import com.helix.app.plugin.PluginReadinessDetails
import com.helix.app.plugin.PluginReadinessSnapshot
import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import org.junit.Rule
import org.junit.Test

class PluginReadinessDetailsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val state =
        PluginReadinessSnapshot(
            "package-a",
            4,
            true,
            true,
            listOf(
                PluginComponentFact(
                    "server-a",
                    PluginComponentKind.MCP,
                    PluginComponentState.NOT_CONNECTED,
                    true,
                    false,
                    "https://example.invalid",
                    "account-a",
                ),
            ),
            emptyList(),
        )

    @Test fun technicalIdentityIsCollapsedAndOnlyExplicitClickRevealsIt() {
        compose.setContent { MaterialTheme { Column { PluginReadinessDetails(state) } } }
        compose.onNodeWithText("account-a").assertDoesNotExist()
        compose.onNodeWithTag("plugin-readiness-package-a").performClick()
        compose.onNodeWithText("account-a").assertIsDisplayed()
        compose.onNodeWithText("https://example.invalid").assertIsDisplayed()
        compose.onNodeWithTag("plugin-readiness-package-a").performClick()
        compose.onNodeWithText("account-a").assertDoesNotExist()
    }

    @Test fun replacingThePackageDoesNotReplayExpandedDetails() {
        val facts = mutableStateOf(state)
        compose.setContent { MaterialTheme { Column { PluginReadinessDetails(facts.value) } } }
        compose.onNodeWithTag("plugin-readiness-package-a").performClick()
        compose.onNodeWithText("account-a").assertIsDisplayed()
        compose.runOnIdle { facts.value = state.copy(pluginId = "package-b") }
        compose.onNodeWithText("account-a").assertDoesNotExist()
        compose.onNodeWithTag("plugin-readiness-package-b").assertIsDisplayed()
    }

    @Test fun contentReadyButInvocationDeniedShowsRequestReason() {
        val facts =
            state.copy(
                components =
                    listOf(
                        state.components.single().copy(
                            kind = PluginComponentKind.SKILL,
                            state = PluginComponentState.AVAILABLE,
                            loaded = true,
                            requestAvailable = false,
                        ),
                    ),
            )
        compose.setContent { MaterialTheme { Column { PluginReadinessDetails(facts) } } }
        compose.onNodeWithTag("plugin-readiness-package-a").performClick()
        val context =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext
        compose
            .onNodeWithText(context.getString(com.helix.app.R.string.plugin_state_request_unavailable))
            .assertIsDisplayed()
    }
}
