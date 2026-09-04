package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-068 consumer-variant absence (ADR-0005/0006): the consumer channel never offers Advanced,
 * so its profile can never be ADVANCED and the bounded high-sensitivity egress-rule section is
 * NEVER rendered — there is no entry to create, list or revoke such a rule. The consumer build
 * cannot reach the section even though the composable itself lives in the shared `src/main`.
 */
@RunWith(AndroidJUnit4::class)
class EgressRuleConsumerAbsenceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun consumerBuildRendersNoEgressRuleSection() {
        composeRule.resetDeterministicUiState()
        composeRule.navigateTo("settings")
        // Consumer build: Standard is fixed, no Advanced entry...
        composeRule.onNodeWithTag("settings-advanced-absent").assertIsDisplayed()
        // ...and therefore the ADVANCED-only egress-rule section is absent.
        assertTrue(
            "the consumer build must never render the ADVANCED-only egress-rule section",
            composeRule.onAllNodesWithTag("egress-section").fetchSemanticsNodes().isEmpty(),
        )
    }
}
