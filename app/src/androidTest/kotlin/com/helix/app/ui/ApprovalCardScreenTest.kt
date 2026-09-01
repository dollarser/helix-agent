package com.helix.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalCardUi
import com.helix.app.approval.BoundedRuleUi
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-036 approval card fixture: the full authorization summary renders every mandated
 * field (来源、目标、scope、参数、风险、Safety Profile、Provider/MCP ID、网络 origin、
 * 数据驻留、数据类别、规则有效期、代码/命令、预期影响、verifier) and the action surface is
 * EXACTLY the two buttons "本次批准" / "拒绝" — no "模型帮我批准", no "此后全部允许",
 * and a bounded Policy rule is labeled as such (never a general approval credential).
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
            display = "api.example.com / contacts / 有效期至 1900000",
        )

    private val card =
        ApprovalCardUi(
            approvalId = "approval-1",
            bindingHash = "a".repeat(64),
            state = ApprovalCardState.PENDING,
            source = "MCP 服务器：srv-7",
            target = "本机（主应用进程）",
            scope = "workspace:ws-9",
            arguments = """{"command":"git pull --ff-only"}""",
            risk = "L1（低风险） → 动态 L2（需逐次批准）",
            profile = SafetyProfile.STANDARD,
            providerMcpId = "srv-7",
            networkOrigin = "https://api.example.com:443",
            residence = "中国大陆",
            dataCategory = "高敏内容（逐次确认）",
            boundedRule = rule,
            codeOrCommand = "git pull --ff-only",
            expectedImpact = "从远端更新 Workspace（可能失败）",
            verifier = "输出必须通过注册的 outputSchema 校验；全量输出记录 SHA-256 哈希",
            confirmationDetail = "该动作将立即执行，无法撤销。",
            terminalDetail = null,
        )

    private fun render() {
        composeRule.setContent {
            ApprovalCard(card, onApprove = {}, onDeny = {})
        }
        composeRule.waitForIdle()
    }

    @Test
    fun cardShowsEveryMandatedField() {
        render()
        composeRule.onNodeWithTag("approval-card-approval-1").assertIsDisplayed()
        // 来源 / 目标 / scope
        composeRule.onNodeWithText("来源：MCP 服务器：srv-7").assertIsDisplayed()
        composeRule.onNodeWithText("目标：本机（主应用进程）").assertIsDisplayed()
        composeRule.onNodeWithText("作用域：workspace:ws-9").assertIsDisplayed()
        // 参数（the FULL canonical arguments — not truncated）
        composeRule.onNodeWithText("参数：{\"command\":\"git pull --ff-only\"}").assertIsDisplayed()
        // 风险（dynamic uplift visible）
        composeRule.onNodeWithText("风险：L1（低风险） → 动态 L2（需逐次批准）").assertIsDisplayed()
        // Safety Profile
        composeRule.onNodeWithText("Safety Profile：Standard（默认）").assertIsDisplayed()
        // Provider/MCP ID
        composeRule.onNodeWithText("Provider/MCP：srv-7").assertIsDisplayed()
        // 网络 origin + 数据驻留
        composeRule.onNodeWithText("网络 origin：https://api.example.com:443").assertIsDisplayed()
        composeRule.onNodeWithText("数据驻留：中国大陆").assertIsDisplayed()
        // 数据类别
        composeRule.onNodeWithText("数据类别：高敏内容（逐次确认）").assertIsDisplayed()
        // 有界 Policy 规则 — labeled as bounded, never a general credential
        composeRule
            .onNodeWithText("有界 Policy 规则（非通用批准凭证）：api.example.com / contacts / 有效期至 1900000")
            .assertIsDisplayed()
        // 代码/命令
        composeRule.onNodeWithText("代码/命令：git pull --ff-only").assertIsDisplayed()
        // 预期影响
        composeRule.onNodeWithText("预期影响：从远端更新 Workspace（可能失败）").assertIsDisplayed()
        // verifier
        composeRule
            .onNodeWithText("校验器（verifier）：输出必须通过注册的 outputSchema 校验；全量输出记录 SHA-256 哈希")
            .assertIsDisplayed()
        // confirmation detail
        composeRule.onNodeWithText("该动作将立即执行，无法撤销。").assertIsDisplayed()
    }

    @Test
    fun actionSurfaceIsExactlyTwoButtons() {
        render()
        composeRule.onNodeWithTag("approval-approve-approval-1").assertIsDisplayed()
        composeRule.onNodeWithTag("approval-deny-approval-1").assertIsDisplayed()
        // The exact labels, rendered from ApprovalCardUi.ACTIONS.
        composeRule.onNodeWithText("本次批准").assertIsDisplayed()
        composeRule.onNodeWithText("拒绝").assertIsDisplayed()
        // No model-self-approval, no permanent allow: the ONLY clickable nodes in the
        // whole composition are the two action buttons (the card is the only content).
        val clickable =
            composeRule
                .onAllNodes(SemanticsMatcher("all") { true }, true)
                .fetchSemanticsNodes()
                .filter { node -> isClickable(node) }
        assertEquals("exactly two clickable nodes (本次批准 / 拒绝)", 2, clickable.size)
        val clickableTexts = clickable.map { nodeText(it) }.toSet()
        assertEquals(setOf("本次批准", "拒绝"), clickableTexts)
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
            ApprovalCard(
                card = card.copy(state = ApprovalCardState.DENIED, terminalDetail = "用户已拒绝本次动作"),
                onApprove = {},
                onDeny = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("approval-card-state-approval-1").assertIsDisplayed()
        composeRule.onNodeWithText("已拒绝：用户已拒绝本次动作").assertIsDisplayed()
        // No action buttons in the terminal state.
        val clickable =
            composeRule
                .onAllNodes(SemanticsMatcher("all") { true }, true)
                .fetchSemanticsNodes()
                .filter { node -> isClickable(node) }
        assertEquals(0, clickable.size)
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
