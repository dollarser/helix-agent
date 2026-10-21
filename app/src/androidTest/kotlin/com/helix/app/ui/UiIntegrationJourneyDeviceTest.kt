package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.MainActivity
import org.junit.Rule
import org.junit.Test

/** Read-only page discovery and real pixels. No login, model request, grant, cleanup or installation action. */
class UiIntegrationJourneyDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualSecondaryPagesRemainDiscoverableAndReadable() {
        compose.resetDeterministicUiState()
        prepareChatLayoutLanguage(compose)
        try {
            listOf(
                "projects" to "screen-projects",
                "knowledge" to "knowledge-search",
                "tasks" to "screen-tasks",
                "artifacts" to "screen-artifacts",
                "models" to "screen-models",
                "extensions" to "screen-extensions",
                "settings" to "screen-settings",
                SETTINGS_DEFAULTS_ROUTE to "screen-settings-defaults",
                SETTINGS_PERMISSIONS_ROUTE to "screen-settings-permissions",
                SETTINGS_SYSTEM_PERMISSIONS_ROUTE to "screen-permissions",
                SETTINGS_STORAGE_ROUTE to "screen-settings-storage",
                SETTINGS_AUDIT_ROUTE to "screen-audit",
                SETUP_READINESS_ROUTE to "capability-readiness",
                SETUP_CAPABILITIES_ROUTE to "screen-capabilities",
                SETUP_RUNTIME_ROUTE to "screen-setup-runtime",
            ).forEach { (route, tag) ->
                compose.navigateTo(route)
                if (route == "knowledge") {
                    compose.onNodeWithTag("knowledge-list").performScrollToNode(hasTestTag(tag))
                }
                compose.onNodeWithTag(tag).assertIsDisplayed()
                compose.waitForIdle()
                captureChatLayout(compose.activity, "integration-${route.replace('/', '-')}")
            }
        } finally {
            prepareChatLayoutLanguage(compose, restore = true)
        }
    }
}
