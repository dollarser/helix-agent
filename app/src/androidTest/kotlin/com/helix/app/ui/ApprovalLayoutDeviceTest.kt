package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalCardUi
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ApprovalLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishLongApprovalKeepsBothActionsReachable() = verify(AppLanguage.EN)

    @Test fun chineseLongApprovalKeepsBothActionsReachable() = verify(AppLanguage.ZH_CN)

    private fun verify(language: AppLanguage) {
        val context =
            AppLanguageStore.wrapForLocale(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppLanguageStore.localeListFor(language),
            )
        val card = layoutApproval()
        var approved = 0
        var denied = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(
                        Modifier
                            .width(240.dp)
                            .height(400.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("approval-viewport"),
                    ) { ApprovalCard(card, { approved++ }, { denied++ }) }
                }
            }
        }
        compose
            .onNodeWithTag(
                "approval-card-args",
            ).performScrollTo()
            .assertTextContains(card.arguments, substring = true)
        val area = compose.onNodeWithTag("approval-viewport").getUnclippedBoundsInRoot()
        listOf("approval-approve-layout", "approval-deny-layout").forEach { tag ->
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag needs a usable width", bounds.right - bounds.left >= 48.dp)
            assertTrue("$tag must fit horizontally: $bounds", bounds.left >= area.left && bounds.right <= area.right)
            assertTrue("$tag must fit vertically", bounds.top >= area.top && bounds.bottom <= area.bottom)
            node.performClick()
        }
        compose.runOnIdle {
            assertEquals(1, approved)
            assertEquals(1, denied)
        }
    }
}

private fun layoutApproval() =
    ApprovalCardUi(
        approvalId = "layout",
        bindingHash = "a".repeat(64),
        state = ApprovalCardState.PENDING,
        sourceRes = R.string.approval_source_mcp,
        sourceArgs = listOf("layout-fixture"),
        targetRes = R.string.approval_target_local_android,
        scope = "workspace:layout",
        arguments = "Long disclosed arguments 完整参数 ".repeat(24),
        riskRes = R.string.approval_risk_l2,
        riskArgs = emptyList(),
        profile = SafetyProfile.STANDARD,
        providerMcpId = "layout-fixture",
        networkOrigin = "https://example.com:443",
        residence = "fixture",
        dataCategoryRes = R.string.approval_category_sensitive,
        boundedRule = null,
        codeOrCommand = "UI fixture only",
        expectedImpact = "No tool executes in this component test.",
        verifierRes = R.string.approval_verifier_text,
        confirmationDetail = "Review the disclosed arguments.",
        terminalDetail = null,
    )
