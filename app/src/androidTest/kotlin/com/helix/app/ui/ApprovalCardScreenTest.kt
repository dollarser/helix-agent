package com.helix.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalCardUi
import com.helix.app.approval.BoundedRuleUi
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolApprovalPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-036 approval card fixture: the full authorization summary renders every mandated
 * field (来源、目标、scope、参数、风险、Safety Profile、Provider/MCP ID、网络 origin、
 * 数据驻留、数据类别、规则有效期、代码/命令、预期影响、verifier) and the ONE-TIME decision
 * surface is EXACTLY the two buttons "本次批准" / "拒绝" — no "模型帮我批准", no
 * "此后全部允许", and a bounded Policy rule is labeled as such (never a general approval
 * credential). HXA-201: the first screen shows the change/egress SUMMARY and the full
 * fields expand via the details toggle (the only non-decision clickable), and the separate
 * "save future preference" group is NOT an action on this call.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalCardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rule =
        BoundedRuleUi(
            targetId = "rule-1",
            origin = "https://api.example.com",
            categories = "contacts",
            scope = "workspace:ws-9",
            expiresAt = 1_900_000L,
            displayRes = R.string.approval_bounded_rule_display,
            displayArgs = listOf("https://api.example.com", "contacts", "workspace:ws-9", "1900000"),
        )

    private val card =
        ApprovalCardUi(
            approvalId = "approval-1",
            bindingHash = "a".repeat(64),
            toolName = "mcp.srv7.git_pull",
            sourceRef = "mcp:srv-7:2025-03-26:" + "cd".repeat(32),
            baseRisk = RiskLevel.L1,
            state = ApprovalCardState.PENDING,
            sourceRes = R.string.approval_source_mcp,
            sourceArgs = listOf("srv-7"),
            targetRes = R.string.approval_target_local_android,
            scope = "workspace:ws-9",
            arguments = """{"command":"git pull --ff-only"}""",
            riskRes = R.string.approval_risk_dynamic_upgrade,
            riskArgs = listOf(R.string.approval_risk_l1, R.string.approval_risk_l2),
            profile = SafetyProfile.STANDARD,
            providerMcpId = "srv-7",
            networkOrigin = "https://api.example.com:443",
            residence = "中国大陆",
            dataCategoryRes = R.string.approval_category_sensitive,
            boundedRule = rule,
            codeOrCommand = "git pull --ff-only",
            expectedImpact = "从远端更新 Workspace（可能失败）",
            verifierRes = R.string.approval_verifier_text,
            confirmationDetail = "该动作将立即执行，无法撤销。",
            terminalDetail = null,
        )

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides canonicalZhContext()) {
                ApprovalCard(card, onApprove = {}, onDeny = {})
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun cardShowsEveryMandatedField() {
        render()
        composeRule.onNodeWithTag("approval-card-approval-1").assertIsDisplayed()
        // HXA-201 first screen: the change/egress SUMMARY, not the full parameter dump.
        // (The summary renders the target values as JSON literals — `command: "git pull …"`.)
        composeRule
            .onNodeWithTag("approval-card-summary-approval-1", useUnmergedTree = true)
            .assertTextEquals("command: \"git pull --ff-only\"")
        // The FULL fields expand via the details toggle. Existence, not display: the
        // Compose test window height flakes across installs and must not gate the
        // mandated-field contract (the first-screen visibility is asserted above).
        composeRule.onNodeWithTag("approval-details-approval-1").performClick()
        composeRule.waitForIdle()
        listOf(
            // 来源 / 目标 / scope
            "来源：MCP 服务器：srv-7",
            "目标：本机（主应用进程）",
            "作用域：workspace:ws-9",
            // 参数（the FULL canonical arguments — not truncated）
            "参数：{\"command\":\"git pull --ff-only\"}",
            // 风险（dynamic uplift visible）
            "风险：L1（低风险） → 动态 L2（需逐次批准）",
            // 权限配置（Safety Profile）
            "权限配置：Standard（默认）",
            // 模型服务／MCP（provider id）
            "模型服务／MCP：srv-7",
            // 网络 origin + 数据驻留
            "网络 origin：https://api.example.com:443",
            "数据驻留：中国大陆",
            // 数据类别
            "数据类别：高敏内容（逐次确认）",
            // 有界 Policy 规则 — labeled as bounded, never a general credential
            "有界 Policy 规则（非通用批准凭证）：origin https://api.example.com · contacts · " +
                "作用域 workspace:ws-9 · 有效期至 1900000（一次授权，非通用凭证）",
            // 代码/命令
            "代码/命令：git pull --ff-only",
            // 预期影响
            "预期影响：从远端更新 Workspace（可能失败）",
            // verifier
            "校验器（verifier）：输出必须通过注册的 outputSchema 校验；全量输出记录 SHA-256 哈希",
            // confirmation detail
            "该动作将立即执行，无法撤销。",
        ).forEach {
            composeRule.onNodeWithText(it).fetchSemanticsNode()
        }
    }

    @Test
    fun actionSurfaceIsExactlyTwoButtons() {
        render()
        composeRule.onNodeWithTag("approval-approve-approval-1").assertIsDisplayed()
        composeRule.onNodeWithTag("approval-deny-approval-1").assertIsDisplayed()
        // The exact labels, rendered from ApprovalCardUi.ACTIONS.
        composeRule.onNodeWithText("本次批准").assertIsDisplayed()
        composeRule.onNodeWithText("拒绝").assertIsDisplayed()
        // No model-self-approval, no permanent allow: the ONLY decision actions in the
        // whole composition are the two buttons (render() wires no future-preference save);
        // the sole other clickable is the details expander, which is not an approval action.
        val clickable =
            composeRule
                .onAllNodes(SemanticsMatcher("all") { true }, true)
                .fetchSemanticsNodes()
                .filter { node -> isClickable(node) }
        assertEquals(
            "exactly three clickable nodes (本次批准 / 拒绝 / details expander)",
            3,
            clickable.size,
        )
        val clickableTags =
            clickable
                .map { it.config.getOrElse(SemanticsProperties.TestTag) { "" } }
                .filter { it.isNotEmpty() }
                .toSet()
        assertEquals(
            setOf("approval-approve-approval-1", "approval-deny-approval-1", "approval-details-approval-1"),
            clickableTags,
        )
    }

    @Test
    fun noForbiddenApprovalShortcutsExistAsActions() {
        render()
        // "模型帮我批准" / "此后全部允许" must not exist as ACTIONABLE text: they may
        // appear only in the footnote stating the product does not offer them.
        val actionable =
            composeRule
                .onAllNodes(SemanticsMatcher("all") { true }, true)
                .fetchSemanticsNodes()
                .filter { node -> isClickable(node) }
                .map { node -> nodeText(node) }
        assertTrue(
            "no model-self-approve action",
            actionable.none { it.contains("模型帮我批准") },
        )
        assertTrue("no permanent-allow action", actionable.none { it.contains("此后全部允许") })
        // The explicit no-permanent-allow footnote IS present.
        composeRule.onNodeWithTag("approval-card-no-permanent-allow").assertIsDisplayed()
    }

    @Test
    fun terminalCardShowsStateInsteadOfButtons() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides canonicalZhContext()) {
                ApprovalCard(
                    card = card.copy(state = ApprovalCardState.DENIED, terminalDetail = "用户已拒绝本次动作"),
                    onApprove = {},
                    onDeny = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("approval-card-state-approval-1").assertIsDisplayed()
        composeRule.onNodeWithText("已拒绝：用户已拒绝本次动作").assertIsDisplayed()
        // No DECISION actions in the terminal state: the only clickable left is the
        // details expander (a stale card can never approve a new call).
        val clickable =
            composeRule
                .onAllNodes(SemanticsMatcher("all") { true }, true)
                .fetchSemanticsNodes()
                .filter { node -> isClickable(node) }
        assertEquals(1, clickable.size)
        assertEquals(
            "approval-details-approval-1",
            clickable.single().config.getOrElse(SemanticsProperties.TestTag) { "" },
        )
    }

    // ------------------------------------------------------------------ HXA-201 slice 2:
    // future-preference actions are SEPARATE from the one-time approve/deny.

    @Test
    fun lowRiskCardOffersFutureAllowAskAndDenySeparateFromTheOneTimeDecision() {
        var saved: ToolApprovalPreference? = null
        var approved = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides canonicalZhContext()) {
                ApprovalCard(
                    card = card, // baseRisk = L1
                    onApprove = { approved++ },
                    onDeny = {},
                    onSaveFuturePreference = { saved = it },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("approval-future-caption-approval-1").assertIsDisplayed()
        composeRule.onNodeWithTag("approval-future-allow-approval-1").assertIsDisplayed()
        composeRule.onNodeWithTag("approval-future-ask-approval-1").assertIsDisplayed()
        composeRule.onNodeWithTag("approval-future-deny-approval-1").assertIsDisplayed()
        // L1 → no high-risk note.
        composeRule.onNodeWithTag("approval-future-note-approval-1").assertDoesNotExist()
        // Saving a future preference does NOT approve the pending call…
        composeRule.onNodeWithTag("approval-future-allow-approval-1").performClick()
        composeRule.runOnIdle {
            assertEquals(ToolApprovalPreference.ALLOW, saved)
            assertEquals("saving a preference must not approve the pending call", 0, approved)
        }
        // …and the one-time approve still works on its own.
        composeRule.onNodeWithTag("approval-approve-approval-1").performClick()
        composeRule.runOnIdle { assertEquals(1, approved) }
    }

    @Test
    fun highRiskCardWithholdsFutureAllowAndShowsTheSettingsNote() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides canonicalZhContext()) {
                ApprovalCard(
                    card = card.copy(baseRisk = RiskLevel.L2),
                    onApprove = {},
                    onDeny = {},
                    onSaveFuturePreference = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("approval-future-allow-approval-1").assertDoesNotExist()
        composeRule.onNodeWithTag("approval-future-ask-approval-1").assertIsDisplayed()
        composeRule.onNodeWithTag("approval-future-deny-approval-1").assertIsDisplayed()
        composeRule.onNodeWithTag("approval-future-note-approval-1").assertIsDisplayed()
    }

    @Test
    fun terminalCardShowsNoFuturePreferenceActions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides canonicalZhContext()) {
                ApprovalCard(
                    card = card.copy(state = ApprovalCardState.APPROVED),
                    onApprove = {},
                    onDeny = {},
                    onSaveFuturePreference = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("approval-future-caption-approval-1").assertDoesNotExist()
        composeRule.onNodeWithTag("approval-future-allow-approval-1").assertDoesNotExist()
        composeRule.onNodeWithTag("approval-future-ask-approval-1").assertDoesNotExist()
        composeRule.onNodeWithTag("approval-future-deny-approval-1").assertDoesNotExist()
    }

    /** A node's own text plus all descendant text (button labels live on child Text nodes). */
    private fun nodeText(node: SemanticsNode): String =
        node.config
            .getOrElse(SemanticsProperties.ContentDescription) { emptyList<String>() }
            .joinToString("") +
            node.config
                .getOrElse(SemanticsProperties.Text) { emptyList<AnnotatedString>() }
                .joinToString("") { it.text } +
            node.children.joinToString("") { nodeText(it) }

    private fun isClickable(node: SemanticsNode): Boolean = node.config.contains(SemanticsActions.OnClick)
}
