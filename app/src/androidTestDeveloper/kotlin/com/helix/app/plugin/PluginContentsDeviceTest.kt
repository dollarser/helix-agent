package com.helix.app.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
                com.helix.app.ui
                    .ExtensionsScreen(null, null, service)
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
        compose.onNodeWithTag("extensions-search").performTextReplacement("no-such-plugin-fixture")
        compose.onNodeWithTag("bundled-plugin-mobile-use").assertDoesNotExist()
        compose.onNodeWithTag("extensions-search").performTextReplacement("Mobile Use")
        compose.onNodeWithTag("plugin-contents-mobile-use").assertDoesNotExist()
        compose.onNodeWithTag("bundled-plugin-mobile-use").performClick()
        val mobileId = service.list().single { it.native?.pluginId == "mobile-use" }.id
        compose.onNodeWithTag("plugin-enabled-$mobileId").assertDoesNotExist()
        compose.onNodeWithTag("connector-default-$mobileId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plugin-tab-1-$mobileId").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("plugin-skills-heading-mobile-use"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("android-ui-task").assertDoesNotExist()
        compose.onNodeWithText("ui.snapshot").assertDoesNotExist()
        compose.onNodeWithTag("plugin-required-heading-mobile-use").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-required-content-mobile-use").assertExists()
        compose.onNodeWithTag("plugin-required-heading-mobile-use").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-skills-heading-mobile-use").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-tools-heading-mobile-use").performScrollTo().performClick()
        compose.onNodeWithText("ui.snapshot").performScrollTo().performClick()
        compose
            .onNodeWithTag(
                "plugin-tool-description-ui.snapshot",
                useUnmergedTree = true,
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("android-ui-task").performScrollTo().performClick()
        compose
            .onNodeWithTag(
                "plugin-skill-content-android-ui-task",
                useUnmergedTree = true,
            ).performScrollTo()
            .assertIsDisplayed()
        verifyReferencePreview()
        verifyIndependentSections()
        assertEquals(before, service.list().single { it.native?.pluginId == "mobile-use" }.enabled)
    }

    private fun verifyReferencePreview() {
        val tag = "plugin-resource-android-ui-task-references/android-ui-reference.md"
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose
            .onNodeWithTag(
                "plugin-resource-content-android-ui-task-references/android-ui-reference.md",
            ).assertExists()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun verifyIndependentSections() {
        compose.onNodeWithTag("plugin-tools-heading-mobile-use").performScrollTo().performClick()
        compose.onNodeWithText("ui.snapshot").assertDoesNotExist()
        compose.onNodeWithText("android-ui-task").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mobile-use-plugin-settings").assertDoesNotExist()
        compose.onNodeWithTag("plugin-tools-heading-mobile-use").performScrollTo().performClick()
        compose.onNodeWithTag("plugin-skills-heading-mobile-use").performScrollTo().performClick()
        compose.onNodeWithText("android-ui-task").assertDoesNotExist()
        compose.onNodeWithText("ui.snapshot").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plugin-tab-0-bundled-plugin:mobile-use").performScrollTo().performClick()
        compose.onNodeWithTag("mobile-use-plugin-settings").performScrollTo().assertIsDisplayed()
    }
}
