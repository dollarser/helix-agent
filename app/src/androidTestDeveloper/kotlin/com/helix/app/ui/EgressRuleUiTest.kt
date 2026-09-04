package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

/**
 * HXA-068 developer-variant bounded egress-rule management (ADR-0005/0012).
 *
 * The section is ADVANCED-only: absent in Standard, present (and usable) after the risk-gated
 * switch. A created rule is bound to an exact provider/MCP id + canonical origin + the app's
 * scope + a fixed TTL, round-trips through Room, revokes, and REHYDRATES after activity
 * recreation (process-restart path). Switching back to Standard HIDES the section without
 * deleting the rule (ADR-0012: profile switch fails closed, it does not erase the store).
 * Expiry + clock-rollback are the Policy Engine's `isLiveFor` job (JVM: PolicyEngineTest).
 */
@RunWith(AndroidJUnit4::class)
class EgressRuleUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // one end-to-end scenario (create, revoke, rehydrate, hide-in-Standard)
    fun advancedEgressRulesCreateListRevokePersistAndHideInStandard() {
        composeRule.resetDeterministicUiState()
        val container = composeRule.container()
        val repo = container.storage.highSensitivityRules
        clearRules(repo)

        // --- Standard (after reset): the egress section is ABSENT ---
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("settings-profile-current").assertIsDisplayed()
        assertTrue(
            "egress section must be absent in Standard",
            composeRule.onAllNodesWithTag("egress-section").fetchSemanticsNodes().isEmpty(),
        )

        // --- switch to Advanced (risk-gated) ---
        composeRule.onNodeWithTag("settings-advanced-switch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-risk-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-risk-confirm").performClick()
        composeRule.waitForIdle()

        // --- Advanced: the section is present and initially empty ---
        composeRule.onNodeWithTag("egress-section-title").performScrollTo()
        composeRule.onNodeWithTag("egress-section-title").assertIsDisplayed()
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
        composeRule.navigateTo("settings")
        composeRule.onNodeWithTag("egress-section-title").performScrollTo()
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

        // --- back to Standard: the section hides, but the rule is NOT deleted ---
        // The exit button lives in the profile section at the TOP of the scrollable settings list,
        // but the test just scrolled down to the egress section (below); scroll it back into view
        // first (as every other click in this test does). An off-screen performClick delivers no
        // hit, so without this the switch silently no-ops and the section never leaves.
        composeRule.onNodeWithTag("settings-advanced-exit").performScrollTo()
        composeRule.onNodeWithTag("settings-advanced-exit").performClick()
        // switchTo(STANDARD) is synchronous, but the section-removal recomposition is driven by the
        // profile StateFlow collector, which a single waitForIdle can return before it lands - poll
        // the node's removal (a real switch regression would time out loudly here).
        waitForNodeAbsent("egress-section")
        assertTrue(
            "egress section must be absent after switching back to Standard",
            composeRule.onAllNodesWithTag("egress-section").fetchSemanticsNodes().isEmpty(),
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

    /**
     * Polls until NO semantics node with [testTag] remains (up to ~10s) — the inverse of
     * [waitForNode]. Used for the negative case (the section hiding after switching back to
     * Standard): the profile change flips the section gate, but the removal recomposition is driven
     * by the profile StateFlow collector, which a single [waitForIdle] can return before it lands —
     * so polling the node's absence is the race-free gate. A real profile-switch regression would
     * time out loudly here rather than pass.
     */
    private fun waitForNodeAbsent(testTag: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isNotEmpty()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for node '$testTag' to be removed" }
            Thread.sleep(100)
        }
    }
}
