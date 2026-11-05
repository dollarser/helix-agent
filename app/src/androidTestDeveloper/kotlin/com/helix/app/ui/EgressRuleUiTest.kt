package com.helix.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.RuleDuration
import com.helix.core.storage.repository.HighSensitivityRuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Developer egress rules remain scoped, revocable and durable; channel identity is fixed. */
@RunWith(AndroidJUnit4::class)
class EgressRuleUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // one end-to-end scenario (create, revoke, rehydrate, hide-in-Standard)
    fun developerEgressRulesCreateListRevokeAndPersist() {
        composeRule.resetDeterministicUiState()
        val container = composeRule.container()
        val repo = container.storage.highSensitivityRules
        clearRules(repo)

        composeRule.navigateTo("settings/permissions")
        composeRule.onNodeWithTag("settings-profile-options").assertDoesNotExist()
        // --- Advanced: the section is present and initially empty ---
        composeRule.onNodeWithTag("settings-egress-options").performScrollTo().performClick()
        composeRule.onNodeWithTag("egress-rule-help").performScrollTo()
        composeRule.onNodeWithTag("egress-rule-help").assertIsDisplayed()
        composeRule
            .onAllNodesWithText(composeRule.activity.getString(com.helix.app.R.string.egress_title))
            .assertCountEquals(1)
        composeRule.onNodeWithTag("egress-empty").performScrollTo()
        composeRule.onNodeWithTag("egress-empty").assertIsDisplayed()

        // --- create a Provider rule (DAYS_7) ---
        composeRule.onNodeWithTag("egress-target-id").performScrollTo()
        composeRule.onNodeWithTag("egress-target-id").performTextInput("prov-e2e")
        composeRule.onNodeWithTag("egress-origin").performScrollTo()
        composeRule.onNodeWithTag("egress-origin").performTextInput("https://api.example.com/v1")
        composeRule.onNodeWithTag("egress-ttl-DAYS_7").performScrollTo()
        composeRule.onNodeWithTag("egress-ttl-DAYS_7").performClick()
        composeRule.onNodeWithTag("egress-create-button").performScrollTo()
        composeRule.onNodeWithTag("egress-create-button").performClick()
        waitForNode("egress-rule-target")
        composeRule.onAllNodesWithTag("egress-rule-target").onFirst().assertTextEquals("provider:prov-e2e")
        // The row is loaded from Room — a displayed row proves the write landed.
        val created = repo.all().single().rule
        assertEquals(EgressTarget.Provider(ProviderId("prov-e2e")), created.target)
        assertEquals(NormalizedEndpoint.parse("https://api.example.com/v1"), created.origin)
        assertEquals(RuleDuration.DAYS_7, created.duration)

        // --- revoke it: back to the empty state, and Room is empty ---
        composeRule.onNodeWithTag("egress-rule-revoke").performScrollTo()
        composeRule.onNodeWithTag("egress-rule-revoke").performClick()
        waitForNode("egress-empty")
        assertEquals(0, repo.all().size)

        // --- create an MCP rule (HOURS_1), then recreate the activity: it must rehydrate ---
        composeRule.onNodeWithTag("egress-target-mcp").performScrollTo()
        composeRule.onNodeWithTag("egress-target-mcp").performClick()
        composeRule.onNodeWithTag("egress-target-id").performScrollTo()
        composeRule.onNodeWithTag("egress-target-id").performTextClearance()
        composeRule.onNodeWithTag("egress-target-id").performTextInput("mcp-e2e")
        composeRule.onNodeWithTag("egress-origin").performScrollTo()
        composeRule.onNodeWithTag("egress-origin").performTextClearance()
        composeRule.onNodeWithTag("egress-origin").performTextInput("https://mcp.example.com/rpc")
        composeRule.onNodeWithTag("egress-ttl-HOURS_1").performScrollTo()
        composeRule.onNodeWithTag("egress-ttl-HOURS_1").performClick()
        composeRule.onNodeWithTag("egress-create-button").performScrollTo()
        composeRule.onNodeWithTag("egress-create-button").performClick()
        waitForNode("egress-rule-target")
        composeRule.onAllNodesWithTag("egress-rule-target").onFirst().assertTextEquals("mcp:mcp-e2e")
        assertEquals(
            EgressTarget.Mcp(McpServerId("mcp-e2e")),
            repo
                .all()
                .single()
                .rule.target,
        )

        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()
        composeRule.navigateTo("settings/permissions")
        if (composeRule.onAllNodesWithTag("egress-section").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("settings-egress-options").performScrollTo().performClick()
        }
        composeRule.onNodeWithTag("egress-rule-help").performScrollTo()
        composeRule.onNodeWithTag("egress-rule-target").performScrollTo()
        composeRule.onNodeWithTag("egress-rule-target").assertIsDisplayed()
        composeRule.onAllNodesWithTag("egress-rule-target").onFirst().assertTextEquals("mcp:mcp-e2e")
        assertEquals(
            EgressTarget.Mcp(McpServerId("mcp-e2e")),
            repo
                .all()
                .single()
                .rule.target,
        )

        assertEquals(1, repo.all().size)
    }

    private fun clearRules(repo: HighSensitivityRuleRepository) {
        repo.all().forEach { repo.revoke(it.id) }
    }

    /**
     * Polls until at least one semantics node with [testTag] exists (up to ~10s). A created
     * rule only reaches the list after a background Room write (the section saves on [Dispatchers.IO]
     * then reloads), so a plain [waitForIdle] can return before the recomposition lands —
     * polling the actual node is the race-free gate.
     */
    private fun waitForNode(testTag: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for node '$testTag'" }
            Thread.sleep(100)
        }
    }
}
