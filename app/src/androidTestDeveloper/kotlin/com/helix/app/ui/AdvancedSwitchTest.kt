package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-028 developer-variant Advanced switch (ADR-0005): explicit switch after
 * the in-app risk explanation; the switch is a PURE state transition —
 * NFR-011 side-effect evidence: the provider service's network-operation
 * counter is unchanged across the switch, and no provider row appears or
 * disappears. The switch persists (the store outlives activity recreation)
 * and is reversible back to STANDARD.
 */
@RunWith(AndroidJUnit4::class)
class AdvancedSwitchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun advancedSwitchIsRiskGatedZeroSideEffectPersistentAndReversible() {
        composeRule.resetDeterministicUiState()
        val container = composeRule.container()
        val networkOpsBefore = container.providerService.networkOperations.value
        val providerRowsBefore = container.providerService.rows.value.size

        // --- Standard: the switch entry exists (developer build) and is risk-gated ---
        composeRule.navigateTo("settings")
        composeRule.onNodeWithText("当前：Standard（默认）").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-advanced-switch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-risk-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("切换为 Advanced 本身不授予任何能力", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("当前版本尚无高级能力被启用", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("settings-risk-confirm").performClick()
        composeRule.waitForIdle()

        // --- switched: the UI shows Advanced and the store persisted it ---
        composeRule.onNodeWithText("当前：Advanced").assertIsDisplayed()
        assertEquals(SafetyProfile.ADVANCED, container.profileStore.profile)

        // --- NFR-011: zero side effects across the switch ---
        assertEquals(
            "switching Advanced must not perform network operations",
            networkOpsBefore,
            container.providerService.networkOperations.value,
        )
        assertEquals(
            "switching Advanced must not touch provider rows",
            providerRowsBefore,
            container.providerService.rows.value.size,
        )

        // --- persistent: the profile survives activity recreation (the store
        //     lives in the Application; process-death persistence is pinned by
        //     SafetyProfileStoreTest on the JVM) ---
        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()
        assertEquals(SafetyProfile.ADVANCED, container.profileStore.profile)
        composeRule.navigateTo("settings")
        composeRule.onNodeWithText("当前：Advanced").assertIsDisplayed()

        // --- reversible: back to Standard without the risk dialog (downgrade) ---
        composeRule.onNodeWithTag("settings-advanced-exit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("当前：Standard（默认）").assertIsDisplayed()
        assertEquals(SafetyProfile.STANDARD, container.profileStore.profile)
    }
}
