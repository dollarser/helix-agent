package com.helix.app

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.ui.PreferenceSettingsTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

class ToolApprovalSettingsLifecycleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<PreferenceSettingsTestActivity>()

    @Test fun realActivityRecreationPreservesSearchScopeAndPersistedPreference() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val model = app.appContainer.toolApprovalSettings
        val workspace =
            model.scopeChoices().first {
                it.scope ==
                    com.helix.core.model.ToolApprovalPreferenceScope.WORKSPACE
            }
        val tool = model.rows(selection = workspace).first()
        compose.waitUntil(10_000) { model.scopeChoices().contains(workspace) }
        compose.onNodeWithTag("preference-scope-picker").performClick()
        compose.onNodeWithTag("preference-scope-${workspace.key}").performScrollTo().performClick()
        compose.onNodeWithTag("settings-tool-approval-search").performTextInput(tool.toolName)
        compose.waitForIdle()
        val prior = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertNotSame(prior, compose.activity)
        compose.onNodeWithTag("preference-scope-picker").assertTextContains(
            compose.activity.getString(
                R.string.preference_scope_workspace,
                compose.activity.getString(R.string.preference_default_workspace),
            ),
            substring = true,
        )
        compose.onNodeWithTag("settings-tool-approval-search").assertTextContains(tool.toolName)
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithTag(
                    "tool-approval-${tool.toolName}-deny",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithTag(
                "tool-approval-${tool.toolName}-deny",
                useUnmergedTree = true,
            ).performScrollTo()
            .performClick()
        compose.waitUntil(10_000) {
            model.rows(selection = workspace).first { it.toolName == tool.toolName }.state ==
                com.helix.app.approval.ToolApprovalSettingsState.DENY
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(
            com.helix.app.approval.ToolApprovalSettingsState.DENY,
            model
                .rows(selection = workspace)
                .first {
                    it.toolName ==
                        tool.toolName
                }.state,
        )
        kotlinx.coroutines.runBlocking { model.restoreDefault(tool) }
    }
}
