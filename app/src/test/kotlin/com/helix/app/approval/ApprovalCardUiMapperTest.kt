package com.helix.app.approval

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
        assertEquals(listOf("本次批准", "拒绝"), ApprovalCardUi.ACTIONS)
        assertEquals("无出网", ApprovalCardUi.NO_EGRESS)
    }

    // ------------------------------------------------------------------ risk / profile / state

    @Test
    fun riskLabelShowsDynamicUplift() {
        assertEquals("L1（低风险）", ApprovalUiMapper.riskLabel(RiskLevel.L1, RiskLevel.L1))
        assertEquals(
            "L1（低风险） → 动态 L2（需逐次批准）",
            ApprovalUiMapper.riskLabel(RiskLevel.L1, RiskLevel.L2),
        )
        assertEquals("L2（需逐次批准）", ApprovalUiMapper.riskLabel(RiskLevel.L2, RiskLevel.L2))
    }

    @Test
    fun riskLabelSingleLevel() {
        assertEquals("L0（无风险）", ApprovalUiMapper.riskLabel(RiskLevel.L0))
        assertEquals("L1（低风险）", ApprovalUiMapper.riskLabel(RiskLevel.L1))
        assertEquals("L2（需逐次批准）", ApprovalUiMapper.riskLabel(RiskLevel.L2))
        assertEquals("L3（高风险）", ApprovalUiMapper.riskLabel(RiskLevel.L3))
    }

    @Test
    fun profileLabelNeverClaimsFullAccess() {
        assertEquals("Standard（默认）", ApprovalUiMapper.profileLabel(SafetyProfile.STANDARD))
        assertEquals("Advanced（增强，非完全访问）", ApprovalUiMapper.profileLabel(SafetyProfile.ADVANCED))
    }

    @Test
    fun stateLabelsAreStableChinese() {
        assertEquals("等待批准", ApprovalUiMapper.stateLabel(ApprovalCardState.PENDING))
        assertEquals("已批准，执行中", ApprovalUiMapper.stateLabel(ApprovalCardState.APPROVED))
        assertEquals("已拒绝", ApprovalUiMapper.stateLabel(ApprovalCardState.DENIED))
        assertEquals("已执行成功", ApprovalUiMapper.stateLabel(ApprovalCardState.SUCCEEDED))
        assertEquals("执行失败", ApprovalUiMapper.stateLabel(ApprovalCardState.FAILED))
    }

    // ------------------------------------------------------------------ outcome codes + source

    @Test
    fun codeLabelCoversEveryDispatchOutcomeCode() {
        DispatchOutcomeCode.entries.forEach { code ->
            val label = ApprovalUiMapper.codeLabel(code)
            org.junit.Assert.assertTrue("label for $code must be non-blank", label.isNotBlank())
        }
        assertEquals("成功", ApprovalUiMapper.codeLabel(DispatchOutcomeCode.SUCCESS))
        assertEquals("用户拒绝审批", ApprovalUiMapper.codeLabel(DispatchOutcomeCode.APPROVAL_DENIED))
        assertEquals("本回合已拒绝该动作", ApprovalUiMapper.codeLabel(DispatchOutcomeCode.SAME_TURN_DENIED))
        assertEquals("审批已过期", ApprovalUiMapper.codeLabel(DispatchOutcomeCode.APPROVAL_EXPIRED))
        assertEquals(
            "启动前已取消",
            ApprovalUiMapper.codeLabel(DispatchOutcomeCode.CANCELLED_BEFORE_START),
        )
    }

    @Test
    fun sourceLabel() {
        assertEquals("策略引擎", ApprovalUiMapper.sourceLabel(DecisionSource.POLICY))
        assertEquals("用户", ApprovalUiMapper.sourceLabel(DecisionSource.USER))
        assertEquals("框架", ApprovalUiMapper.sourceLabel(DecisionSource.FRAMEWORK))
    }

    // ------------------------------------------------------------------ source / target / provider

    @Test
    fun sourceAndProviderLabels() {
        assertEquals("内置工具", ApprovalUiMapper.sourceLabel(isMcp = false, serverId = null))
        assertEquals("MCP 服务器：srv-1", ApprovalUiMapper.sourceLabel(isMcp = true, serverId = "srv-1"))
        // The provider/MCP id is the raw server id for MCP tools and null for built-ins —
        // the UI renders the "内置（无 Provider/MCP）" placeholder from that null.
        assertNull(ApprovalUiMapper.providerMcpIdLabel(false, null))
        assertEquals("srv-1", ApprovalUiMapper.providerMcpIdLabel(true, "srv-1"))
    }

    @Test
    fun targetLabelsNameTheIsolationBoundary() {
        assertEquals("本机（主应用进程）", ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_ANDROID))
        assertEquals(
            "本机（隔离 QuickJS 进程）",
            ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_QUICKJS),
        )
        assertEquals("本机（PRoot Runtime 应用）", ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_PROOT))
        assertEquals(
            "本机（CLI Runtime 应用）",
            ApprovalUiMapper.targetLabel(ExecutionTargetType.LOCAL_CLI_RUNTIME),
        )
    }

    // ------------------------------------------------------------------ category / scope / code

    @Test
    fun categoryLabelUsesEgressSensitivityWhenPresent() {
        assertEquals(
            "高敏内容（逐次确认）",
            ApprovalUiMapper.categoryLabel(DataOrigin.WORKSPACE, DataSensitivity.SENSITIVE),
        )
        assertEquals(
            "普通内容",
            ApprovalUiMapper.categoryLabel(DataOrigin.BROWSER, DataSensitivity.NORMAL),
        )
        assertEquals(
            "禁止类内容（不可发送）",
            ApprovalUiMapper.categoryLabel(DataOrigin.WORKSPACE, DataSensitivity.FORBIDDEN),
        )
    }

    @Test
    fun categoryLabelFallsBackToOrigin() {
        assertEquals("Workspace 数据", ApprovalUiMapper.categoryLabel(DataOrigin.WORKSPACE, null))
        assertEquals("SAF 文档树", ApprovalUiMapper.categoryLabel(DataOrigin.SAF, null))
        assertEquals("全文件数据", ApprovalUiMapper.categoryLabel(DataOrigin.ALL_FILES, null))
        assertEquals("浏览器页面内容", ApprovalUiMapper.categoryLabel(DataOrigin.BROWSER, null))
        assertEquals("无障碍内容", ApprovalUiMapper.categoryLabel(DataOrigin.ACCESSIBILITY, null))
        assertEquals("MCP 数据（默认不可信）", ApprovalUiMapper.categoryLabel(DataOrigin.MCP, null))
        assertEquals("Root 数据", ApprovalUiMapper.categoryLabel(DataOrigin.ROOT, null))
        assertEquals("本机数据", ApprovalUiMapper.categoryLabel(DataOrigin.LOCAL, null))
        assertEquals("网络数据", ApprovalUiMapper.categoryLabel(DataOrigin.NETWORK, null))
    }

    @Test
    fun scopeLabel() {
        assertEquals("无作用域（unscoped）", ApprovalUiMapper.scopeLabel("unscoped"))
        assertEquals("workspace:ws-9", ApprovalUiMapper.scopeLabel("workspace:ws-9"))
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
        // Input summary: inline JSON value + byte size — the body is not shown.
        assertTrue(
            "input summary must name the inline JSON source: ${e.inputSource}",
            e.inputSource.startsWith("内联 JSON 值（"),
        )
        assertTrue(e.inputSource.contains("字节）"))
        // The fixed §4.1 limits are displayed (the model cannot change them).
        assertTrue(e.limits.contains("10 s"))
        assertTrue(e.limits.contains("64 MiB"))
        assertTrue(e.limits.contains("256 KiB"))
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
        assertEquals(ApprovalCardUi.NO_INPUT, noInput!!.inputSource)
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
        val rule = BoundedRuleUi("rule-1", "example.com", "contacts", "workspace:ws-9", 1_900_000L, "rule text")

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
        assertEquals("内置工具", card.source)
        assertEquals("本机（主应用进程）", card.target)
        assertEquals("workspace:ws-9", card.scope)
        // 参数 = the SAME canonical bytes the binding hashes (doc 02 section 5.4).
        assertEquals(CanonicalArgs.canonicalize(args), card.arguments)
        assertEquals("L1（低风险） → 动态 L2（需逐次批准）", card.risk)
        assertEquals(SafetyProfile.STANDARD, card.profile)
        assertNull(card.providerMcpId)
        assertEquals("https://example.com/api", card.networkOrigin)
        assertEquals("US", card.residence)
        assertEquals("高敏内容（逐次确认）", card.dataCategory)
        assertEquals("rule text", card.boundedRule?.display)
        assertNull(card.codeOrCommand)
        assertEquals("向 Workspace 写入一个文件（可覆盖已有文件）", card.expectedImpact)
        assertEquals(ApprovalUiMapper.VERIFIER_TEXT, card.verifier)
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
        assertEquals("MCP 服务器：srv-7", card.source)
        assertEquals("srv-7", card.providerMcpId)
        assertEquals("无作用域（unscoped）", card.scope)
        // No egress facts -> null (the UI renders the explicit 无出网).
        assertNull(card.networkOrigin)
        assertNull(card.residence)
        assertEquals("MCP 数据（默认不可信）", card.dataCategory)
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
        // The display line always carries the bounded-rule label and the window.
        assertTrue("display must mark a bounded rule: ${ui.display}", ui.display.contains("非通用凭证"))
        assertTrue("display must carry the origin", ui.display.contains("api.example.com"))
    }

    @Test
    fun boundedRuleUiIsNullWhenNoRuleCoversTheCall() {
        assertNull(ApprovalUiMapper.boundedRuleUi(null))
    }
}
