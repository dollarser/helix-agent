package com.helix.app.approval

import com.helix.app.R
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.DataSensitivity
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.WorkspaceScope
import com.helix.runtime.quickjs.tool.CodeJavascriptRunTool
import com.helix.tools.framework.CanonicalArgs
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-036 (app): the approval card's label/mapping layer — every mandated display field
 * (来源/目标/scope/参数/风险/Profile/Provider-MCP/origin/residence/数据类别/规则有效期/
 * 代码命令/预期影响/verifier) maps from trusted execution facts, with the exact action
 * surface ("本次批准"/"拒绝") and the no-permanent-allow wording.
 */
class ApprovalCardUiMapperTest {
    // ------------------------------------------------------------------ exact action surface

    @Test
    fun actionsAreExactlyThisCallApproveAndDeny() {
        assertEquals(
            listOf(R.string.approval_action_approve_once, R.string.approval_action_deny),
            ApprovalCardUi.ACTIONS,
        )
        assertEquals(R.string.approval_no_egress, ApprovalCardUi.NO_EGRESS)
    }

    // ------------------------------------------------------------------ risk / profile / state

    @Test
    fun riskLabelShowsDynamicUplift() {
        assertEquals(R.string.approval_risk_l1, ApprovalUiMapper.riskLabel(RiskLevel.L1, RiskLevel.L1).first)
        val (upliftRes, upliftArgs) = ApprovalUiMapper.riskLabel(RiskLevel.L1, RiskLevel.L2)
        assertEquals(R.string.approval_risk_dynamic_upgrade, upliftRes)
        assertEquals(listOf(R.string.approval_risk_l1, R.string.approval_risk_l2), upliftArgs)
        assertEquals(R.string.approval_risk_l2, ApprovalUiMapper.riskLabel(RiskLevel.L2, RiskLevel.L2).first)
    }

    @Test
    fun riskLabelSingleLevel() {
        assertEquals(R.string.approval_risk_l0, ApprovalUiMapper.riskLabel(RiskLevel.L0))
        assertEquals(R.string.approval_risk_l1, ApprovalUiMapper.riskLabel(RiskLevel.L1))
        assertEquals(R.string.approval_risk_l2, ApprovalUiMapper.riskLabel(RiskLevel.L2))
        assertEquals(R.string.approval_risk_l3, ApprovalUiMapper.riskLabel(RiskLevel.L3))
    }

    @Test
    fun profileLabelNeverClaimsFullAccess() {
        assertEquals(R.string.approval_profile_standard, ApprovalUiMapper.profileLabel(SafetyProfile.STANDARD))
        assertEquals(
            R.string.approval_profile_advanced,
            ApprovalUiMapper.profileLabel(SafetyProfile.ADVANCED),
        )
    }

    @Test
    fun stateLabelsAreStableChinese() {
        assertEquals(R.string.approval_state_pending, ApprovalUiMapper.stateLabel(ApprovalCardState.PENDING))
        assertEquals(R.string.approval_state_approved, ApprovalUiMapper.stateLabel(ApprovalCardState.APPROVED))
        assertEquals(R.string.approval_state_denied, ApprovalUiMapper.stateLabel(ApprovalCardState.DENIED))
        assertEquals(R.string.approval_state_succeeded, ApprovalUiMapper.stateLabel(ApprovalCardState.SUCCEEDED))
        assertEquals(R.string.approval_state_failed, ApprovalUiMapper.stateLabel(ApprovalCardState.FAILED))
    }

    // ------------------------------------------------------------------ outcome codes + source

    @Test
    fun codeLabelCoversEveryDispatchOutcomeCode() {
        DispatchOutcomeCode.entries.forEach { code ->
            // HXA-069: the label is a stable string-resource ID (0 = no such resource).
            org.junit.Assert.assertTrue(
                "label for $code must be a res id",
                ApprovalUiMapper.codeLabel(code) != 0,
            )
        }
        assertEquals(R.string.approval_code_success, ApprovalUiMapper.codeLabel(DispatchOutcomeCode.SUCCESS))
        assertEquals(
            R.string.approval_code_approval_denied,
            ApprovalUiMapper.codeLabel(DispatchOutcomeCode.APPROVAL_DENIED),
        )
        assertEquals(
            R.string.approval_code_same_turn_denied,
            ApprovalUiMapper.codeLabel(DispatchOutcomeCode.SAME_TURN_DENIED),
        )
        assertEquals(
            R.string.approval_code_approval_expired,
            ApprovalUiMapper.codeLabel(DispatchOutcomeCode.APPROVAL_EXPIRED),
        )
        assertEquals(
            R.string.approval_code_cancelled_before_start,
            ApprovalUiMapper.codeLabel(DispatchOutcomeCode.CANCELLED_BEFORE_START),
        )
    }

    @Test
    fun sourceLabel() {
        assertEquals(R.string.approval_decision_source_policy, ApprovalUiMapper.sourceLabel(DecisionSource.POLICY))
        assertEquals(R.string.approval_decision_source_user, ApprovalUiMapper.sourceLabel(DecisionSource.USER))
        assertEquals(
            R.string.approval_decision_source_framework,
            ApprovalUiMapper.sourceLabel(DecisionSource.FRAMEWORK),
        )
    }

    // ------------------------------------------------------------------ source / target / provider

    @Test
    fun sourceAndProviderLabels() {
        val builtin = ApprovalUiMapper.sourceLabel(isMcp = false, serverId = null)
        assertEquals(R.string.approval_source_builtin, builtin.res)
        assertTrue(builtin.args.isEmpty())
        val mcp = ApprovalUiMapper.sourceLabel(isMcp = true, serverId = "srv-1")
        assertEquals(R.string.approval_source_mcp, mcp.res)
        assertEquals(listOf("srv-1"), mcp.args)
        // The provider/MCP id is the raw server id for MCP tools and null for built-ins —
        // the UI renders the "内置（无 Provider/MCP）" placeholder from that null.
        assertNull(ApprovalUiMapper.providerMcpIdLabel(false, null))
        assertEquals("srv-1", ApprovalUiMapper.providerMcpIdLabel(true, "srv-1"))
    }

    @Test
    fun targetLabelsNameTheIsolationBoundary() {
        assertEquals(
            R.string.approval_target_local_android,
            ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_ANDROID),
        )
        assertEquals(
            R.string.approval_target_local_quickjs,
            ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_QUICKJS),
        )
        assertEquals(
            R.string.approval_target_local_proot,
            ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_PROOT),
        )
        assertEquals(
            R.string.approval_target_local_cli,
            ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_CLI_RUNTIME),
        )
    }

    // ------------------------------------------------------------------ category / scope / code

    @Test
    fun categoryLabelUsesEgressSensitivityWhenPresent() {
        assertEquals(
            R.string.approval_category_sensitive,
            ApprovalUiMapper.categoryLabel(DataOrigin.WORKSPACE, DataSensitivity.SENSITIVE),
        )
        assertEquals(
            R.string.approval_category_normal,
            ApprovalUiMapper.categoryLabel(DataOrigin.BROWSER, DataSensitivity.NORMAL),
        )
        assertEquals(
            R.string.approval_category_forbidden,
            ApprovalUiMapper.categoryLabel(DataOrigin.WORKSPACE, DataSensitivity.FORBIDDEN),
        )
    }

    @Test
    fun categoryLabelFallsBackToOrigin() {
        assertEquals(R.string.approval_category_workspace, ApprovalUiMapper.categoryLabel(DataOrigin.WORKSPACE, null))
        assertEquals(R.string.approval_category_saf, ApprovalUiMapper.categoryLabel(DataOrigin.SAF, null))
        assertEquals(R.string.approval_category_all_files, ApprovalUiMapper.categoryLabel(DataOrigin.ALL_FILES, null))
        assertEquals(R.string.approval_category_browser, ApprovalUiMapper.categoryLabel(DataOrigin.BROWSER, null))
        assertEquals(
            R.string.approval_category_accessibility,
            ApprovalUiMapper.categoryLabel(DataOrigin.ACCESSIBILITY, null),
        )
        assertEquals(R.string.approval_category_mcp, ApprovalUiMapper.categoryLabel(DataOrigin.MCP, null))
        assertEquals(R.string.approval_category_root, ApprovalUiMapper.categoryLabel(DataOrigin.ROOT, null))
        assertEquals(R.string.approval_category_local, ApprovalUiMapper.categoryLabel(DataOrigin.LOCAL, null))
        assertEquals(R.string.approval_category_network, ApprovalUiMapper.categoryLabel(DataOrigin.NETWORK, null))
    }

    @Test
    fun cardScopeKeepsTheStableScopeRef() {
        // HXA-069: the card carries the STABLE scope ref; the UI localizes the "unscoped" ref.
        val card =
            ApprovalUiMapper.buildCard(
                approvalId = "a-scope",
                binding = jsBinding(),
                state = ApprovalCardState.PENDING,
                descriptor = CodeJavascriptRunTool.descriptor(),
                arguments = buildJsonObject { put("code", "return 1") },
                dynamicRisk = RiskLevel.L2,
                profile = SafetyProfile.STANDARD,
                dataOrigin = DataOrigin.WORKSPACE,
                egressOrigin = null,
                egressResidence = null,
                egressCategory = null,
                boundedRule = null,
                confirmationDetail = "",
                terminalDetail = null,
            )
        assertEquals("unscoped", card.scope)
    }

    @Test
    fun codeOrCommandExtractsStringArguments() {
        assertEquals(
            "print(1)",
            ApprovalUiMapper.codeOrCommand(
                buildJsonObject {
                    put("code", "print(1)")
                    put("command", "ignored")
                },
            ),
        )
        assertEquals(
            "ls -la",
            ApprovalUiMapper.codeOrCommand(buildJsonObject { put("command", "ls -la") }),
        )
        assertNull(ApprovalUiMapper.codeOrCommand(buildJsonObject { put("n", 1) }))
        // A non-string code is not a code (fail closed: nothing shown, nothing fabricated).
        assertNull(
            ApprovalUiMapper.codeOrCommand(
                buildJsonObject {
                    put("code", 42)
                },
            ),
        )
    }

    // ------------------------------------------------------------------ code-execution card (HXA-053)

    @Test
    fun codeExecutionCardShowsFullCodeInputSummaryLimitsOfflineAndHash() {
        val code = "return { doubled: input.n * 2 }"
        val args =
            buildJsonObject {
                put("code", code)
                put("input", buildJsonObject { put("n", 21) })
            }
        val ui = ApprovalUiMapper.codeExecutionUi(CodeJavascriptRunTool.descriptor(), args)
        assertNotNull("a CODE_EXECUTION tool with a code arg must render the code section", ui)
        val e = ui!!
        // The FULL code is shown (copyable/searchable), not truncated.
        assertEquals(code, e.code)
        // The code SHA-256 short digest is the first 16 hex chars of the full code hash.
        val expectedShort =
            MessageDigest
                .getInstance("SHA-256")
                .digest(code.toByteArray(StandardCharsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
        assertEquals(expectedShort, e.codeSha256Short)
        // Input summary: inline JSON value + byte size — the body is not shown (HXA-069: res id + arg).
        assertEquals(R.string.approval_input_inline_json, e.inputSourceRes)
        assertTrue(
            "input summary must carry the byte size: ${e.inputSourceArgs}",
            e.inputSourceArgs.size == 1 && e.inputSourceArgs[0].toIntOrNull() != null,
        )
        // The fixed §4.1 limits are displayed (the model cannot change them; HXA-069: res id + args).
        assertEquals(R.string.approval_limits_value, e.limitsRes)
        assertEquals(listOf("10", "64", "256"), e.limitsArgs)
        // 联网：否 (offline) — the QuickJS backend has no network.
        assertFalse("QuickJS must render as offline", e.online)
    }

    @Test
    fun codeExecutionCardIsAbsentForNonCodeToolsAndShowsNoInputWhenInputMissing() {
        // A non-CODE_EXECUTION descriptor never gets the code-execution section.
        val nonCode =
            ToolDescriptor(
                name = ToolName("fs.write"),
                version = ToolVersion(2),
                description = "写入文件",
                inputSchema = Json.parseToJsonElement("""{"type":"object"}""") as kotlinx.serialization.json.JsonObject,
                outputSchema =
                    Json.parseToJsonElement(
                        """{"type":"object"}""",
                    ) as kotlinx.serialization.json.JsonObject,
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L2,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.NON_IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        assertNull(
            ApprovalUiMapper.codeExecutionUi(
                nonCode,
                buildJsonObject { put("code", "print(1)") },
            ),
        )
        // A JS call with NO input renders the explicit "无输入" line (not an empty body).
        val noInput =
            ApprovalUiMapper.codeExecutionUi(
                CodeJavascriptRunTool.descriptor(),
                buildJsonObject { put("code", "return 1") },
            )
        assertEquals(R.string.approval_no_input, noInput!!.inputSourceRes)
        assertTrue(noInput.inputSourceArgs.isEmpty())
    }

    @Test
    fun buildCardAttachesTheCodeExecutionSectionForJs() {
        val card =
            ApprovalUiMapper.buildCard(
                approvalId = "a-js",
                binding = jsBinding(),
                state = ApprovalCardState.PENDING,
                descriptor = CodeJavascriptRunTool.descriptor(),
                arguments =
                    buildJsonObject {
                        put("code", "return { x: 1 }")
                        put("input", buildJsonObject { put("n", 2) })
                    },
                dynamicRisk = RiskLevel.L2,
                profile = SafetyProfile.STANDARD,
                dataOrigin = DataOrigin.WORKSPACE,
                egressOrigin = null,
                egressResidence = null,
                egressCategory = null,
                boundedRule = null,
                confirmationDetail = "",
                terminalDetail = null,
            )
        assertNotNull("buildCard must attach the code-execution section for the JS tool", card.codeExecution)
        // No egress → the card renders the explicit 无出网 (联网：否 is covered by codeExecution.online=false).
        assertNull(card.networkOrigin)
    }

    @Test
    fun buildCardLeavesCodeExecutionNullForNonCodeTools() {
        val card =
            ApprovalUiMapper.buildCard(
                approvalId = "a-plain",
                binding = jsBinding().copy(toolName = "fs.write", executionTarget = ExecutionTargetType.LOCAL_ANDROID),
                state = ApprovalCardState.PENDING,
                descriptor = plainDescriptor(),
                arguments = buildJsonObject { put("path", "/x") },
                dynamicRisk = RiskLevel.L2,
                profile = SafetyProfile.STANDARD,
                dataOrigin = DataOrigin.WORKSPACE,
                egressOrigin = null,
                egressResidence = null,
                egressCategory = null,
                boundedRule = null,
                confirmationDetail = "",
                terminalDetail = null,
            )
        assertNull("a non-code tool must not get a code-execution section", card.codeExecution)
    }

    private fun jsBinding() =
        ApprovalBinding(
            toolCallId = "call-js",
            toolName = "code.javascript.run",
            toolVersion = "1",
            schemaHash = "a".repeat(64),
            contractHash = "b".repeat(64),
            scopeRef = "unscoped",
            sessionId = "sess-1",
            executionTarget = ExecutionTargetType.LOCAL_QUICKJS,
            uiToken = "chat:turn-1",
            argsHash = "c".repeat(64),
        )

    private fun plainDescriptor() =
        ToolDescriptor(
            name = ToolName("fs.write"),
            version = ToolVersion(2),
            description = "写入文件",
            inputSchema = Json.parseToJsonElement("""{"type":"object"}""") as kotlinx.serialization.json.JsonObject,
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""") as kotlinx.serialization.json.JsonObject,
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    // ------------------------------------------------------------------ buildCard (full mapping)

    // One acceptance test per mandated field list (roadmap HXA-036): the length is the
    // 13-field assertion itself — splitting it would fragment the acceptance criterion.
    @Suppress("LongMethod")
    @Test
    fun buildCardMapsEveryMandatedFieldFromTrustedFacts() {
        val descriptor =
            ToolDescriptor(
                name = ToolName("fs.write"),
                version = ToolVersion(2),
                description = "向 Workspace 写入一个文件（可覆盖已有文件）",
                inputSchema =
                    Json.parseToJsonElement("""{"type":"object"}""").let {
                        it as kotlinx.serialization.json.JsonObject
                    },
                outputSchema =
                    Json.parseToJsonElement("""{"type":"object"}""").let {
                        it as kotlinx.serialization.json.JsonObject
                    },
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L1,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.NON_IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        val args =
            buildJsonObject {
                put("path", "/ws/out.txt")
                put("content", "hello")
            }
        val binding =
            ApprovalBinding(
                toolCallId = "call-1",
                toolName = "fs.write",
                toolVersion = "2",
                schemaHash = "b".repeat(64),
                contractHash = "e".repeat(64),
                scopeRef = "workspace:ws-9",
                sessionId = "sess-1",
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                uiToken = "chat:turn-1",
                argsHash = "c".repeat(64),
            )
        val rule =
            BoundedRuleUi(
                targetId = "rule-1",
                origin = "example.com",
                categories = "contacts",
                scope = "workspace:ws-9",
                expiresAt = 1_900_000L,
                displayRes = R.string.approval_bounded_rule_display,
                displayArgs = listOf("example.com", "contacts", "workspace:ws-9", "2000-01-01 00:00"),
            )

        val card =
            ApprovalUiMapper.buildCard(
                approvalId = "approval-1",
                binding = binding,
                state = ApprovalCardState.PENDING,
                descriptor = descriptor,
                arguments = args,
                dynamicRisk = RiskLevel.L2,
                profile = SafetyProfile.STANDARD,
                dataOrigin = DataOrigin.WORKSPACE,
                egressOrigin = "https://example.com/api",
                egressResidence = "US",
                egressCategory = DataSensitivity.SENSITIVE,
                boundedRule = rule,
                confirmationDetail = "detail text",
                terminalDetail = null,
            )

        assertEquals("approval-1", card.approvalId)
        assertEquals(binding.hash, card.bindingHash)
        assertEquals(ApprovalCardState.PENDING, card.state)
        assertEquals(R.string.approval_source_builtin, card.sourceRes)
        assertTrue(card.sourceArgs.isEmpty())
        assertEquals(R.string.approval_target_local_android, card.targetRes)
        assertEquals("workspace:ws-9", card.scope)
        // 参数 = the SAME canonical bytes the binding hashes (doc 02 section 5.4).
        assertEquals(CanonicalArgs.canonicalize(args), card.arguments)
        assertEquals(R.string.approval_risk_dynamic_upgrade, card.riskRes)
        assertEquals(listOf(R.string.approval_risk_l1, R.string.approval_risk_l2), card.riskArgs)
        assertEquals(SafetyProfile.STANDARD, card.profile)
        assertNull(card.providerMcpId)
        assertEquals("https://example.com/api", card.networkOrigin)
        assertEquals("US", card.residence)
        assertEquals(R.string.approval_category_sensitive, card.dataCategoryRes)
        assertEquals(R.string.approval_bounded_rule_display, card.boundedRule?.displayRes)
        assertNull(card.codeOrCommand)
        assertEquals("向 Workspace 写入一个文件（可覆盖已有文件）", card.expectedImpact)
        assertEquals(ApprovalUiMapper.VERIFIER_TEXT, card.verifierRes)
        assertEquals("detail text", card.confirmationDetail)
        assertNull(card.terminalDetail)
    }

    @Suppress("LongMethod") // same as the sibling buildCard test: a full mapping assertion, not to be fragmented
    @Test
    fun buildCardWithoutEgressShowsNoEgressAndMcpSource() {
        val descriptor =
            ToolDescriptor(
                name = ToolName("mcp.tool"),
                version = ToolVersion(1),
                description = "mcp tool",
                inputSchema =
                    Json.parseToJsonElement("""{"type":"object"}""").let {
                        it as kotlinx.serialization.json.JsonObject
                    },
                outputSchema =
                    Json.parseToJsonElement("""{"type":"object"}""").let {
                        it as kotlinx.serialization.json.JsonObject
                    },
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L2,
                timeout = 30.seconds,
                maxOutputBytes = 4096L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.McpOrigin(serverId = "srv-7", protocolVersion = 1),
            )
        val binding =
            ApprovalBinding(
                toolCallId = "c",
                toolName = "mcp.tool",
                toolVersion = "1",
                schemaHash = "b".repeat(64),
                contractHash = "e".repeat(64),
                scopeRef = "unscoped",
                sessionId = "s",
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                uiToken = "chat:t",
                argsHash = "d".repeat(64),
            )
        val card =
            ApprovalUiMapper.buildCard(
                approvalId = "a",
                binding = binding,
                state = ApprovalCardState.PENDING,
                descriptor = descriptor,
                arguments = kotlinx.serialization.json.JsonObject(emptyMap()),
                dynamicRisk = RiskLevel.L2,
                profile = SafetyProfile.STANDARD,
                dataOrigin = DataOrigin.MCP,
                egressOrigin = null,
                egressResidence = null,
                egressCategory = null,
                boundedRule = null,
                confirmationDetail = "d",
                terminalDetail = null,
            )
        assertEquals(R.string.approval_source_mcp, card.sourceRes)
        assertEquals(listOf("srv-7"), card.sourceArgs)
        assertEquals("srv-7", card.providerMcpId)
        assertEquals("unscoped", card.scope)
        // No egress facts -> null (the UI renders the explicit 无出网).
        assertNull(card.networkOrigin)
        assertNull(card.residence)
        assertEquals(R.string.approval_category_mcp, card.dataCategoryRes)
    }

    // ------------------------------------------------------------------ bounded egress rule

    @Test
    fun boundedRuleUiCarriesTheRuleBindingAndWindowAsABoundedCredential() {
        // 高敏出网规则单独标为有界 Policy 规则 (roadmap HXA-036 / ADR-0005): the card must
        // show the rule's exact binding + validity window, labeled as a bounded rule.
        val created = Instant.parse("2026-09-01T00:00:00Z")
        val rule =
            HighSensitivityRule(
                EgressTarget.Provider(ProviderId("provider-1")),
                NormalizedEndpoint.parse("https://api.example.com/v1"),
                DataSensitivity.SENSITIVE,
                WorkspaceScope("ws-9"),
                created,
                created.plus(Duration.ofHours(24)),
            )
        val ui = ApprovalUiMapper.boundedRuleUi(rule)
        assertNotNull(ui)
        assertEquals("provider:provider-1", ui!!.targetId)
        assertEquals("https://api.example.com:443", ui.origin)
        assertEquals("SENSITIVE", ui.categories)
        assertEquals("workspace:ws-9", ui.scope)
        assertEquals(created.plus(Duration.ofHours(24)).toEpochMilli(), ui.expiresAt)
        // The display line (HXA-069: res id + the rule's stable binding args) carries origin + window.
        assertEquals(R.string.approval_bounded_rule_display, ui.displayRes)
        assertTrue("display must carry the origin", ui.displayArgs.first() == "https://api.example.com:443")
        assertEquals("SENSITIVE", ui.displayArgs[1])
        assertEquals("workspace:ws-9", ui.displayArgs[2])
        assertTrue("display must carry the window", ui.displayArgs.size == 4 && ui.displayArgs[3].isNotEmpty())
    }

    @Test
    fun boundedRuleUiIsNullWhenNoRuleCoversTheCall() {
        assertNull(ApprovalUiMapper.boundedRuleUi(null))
    }
}
