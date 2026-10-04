package com.helix.app.plugin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginContentsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clickingPluginRevealsToolsAndOwnedSkillWithoutEnablingIt() {
        val service = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.pluginService
        val before = service.list().single { it.native?.pluginId == "mobile-use" }.enabled
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BundledPluginsSection(service) {}
                }
            }
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("bundled-plugin-mobile-use"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("plugin-contents-mobile-use").assertDoesNotExist()
        compose.onNodeWithTag("bundled-plugin-mobile-use").performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("android-ui-task"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("ui.snapshot").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-tool-description-ui.snapshot").assertExists()
        compose.onNodeWithText("android-ui-task").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-skill-content-android-ui-task").assertExists()
        assertEquals(before, service.list().single { it.native?.pluginId == "mobile-use" }.enabled)
    }
}
