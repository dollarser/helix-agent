package com.helix.app.approval

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolOperationClass
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.DataSensitivity
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.HighSensitivityRule
import com.helix.runtime.quickjs.JsExecutionLimits
import com.helix.tools.framework.CanonicalArgs
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The lifecycle of one approval card in the session timeline (roadmap HXA-036; doc 01
 * FR-CHAT-003 — the fourth timeline type, distinct from model text, tool request and tool
 * result).
 *
 * Transitions: [PENDING] -> (user action) [APPROVED] | [DENIED]; [APPROVED] ->
 * [SUCCEEDED] | [FAILED] (the terminal execution outcome); a call the broker could not make
 * consumable (expired / consumed / not found) goes straight [PENDING] -> [FAILED] with a
 * stable [ApprovalCardUi.terminalDetail].
 */
enum class ApprovalCardState {
    /** Awaiting the user's "本次批准 / 拒绝" decision — the only state with action buttons. */
    PENDING,

    /** The user approved this call; the proof is minted and execution is running. */
    APPROVED,

    /** The user denied this exact action — terminal, audit-only (HXA-034). */
    DENIED,

    /** Execution started and finished successfully; the output passed the registered schema. */
    SUCCEEDED,

    /** Execution did not succeed (denied before start, non-consumable approval, timeout,
     * cancellation, tool failure or invalid output) — terminal, with a stable detail.
     */
    FAILED,
}

/**
 * The display of an ACTIVE bounded Policy rule (ADR-0005) that satisfied the egress this
 * card is about — roadmap HXA-036: 高敏出网规则单独标为有界 Policy 规则.
 *
 * [display] always carries the "有界 Policy 规则" label and the expiry — a bounded rule is
 * NEVER shown as a general approval credential (it cannot authorize file changes, shell,
 * Root, Accessibility or a new origin — doc 02 section 8.1).
 */
data class BoundedRuleUi(
    val targetId: String,
    val origin: String,
    val categories: String,
    val scope: String,
    val expiresAt: Long,
    val display: String,
)

/**
 * The code-execution section of a code-execution tool's approval card (HXA-053; doc 03 §5).
 * Renders the FULL code as a copyable/searchable block, the input SOURCE + size (never the
 * sensitive body by default), the FIXED "联网：否" line, the applied limits, and the code's
 * SHA-256 short digest. Present only for CODE_EXECUTION tools; null otherwise.
 *
 * The limits come from the fixed §4.1 defaults the backend enforces ([JsExecutionLimits]
 * DEFAULTS) — never from model arguments (the model cannot raise them).
 */
data class CodeExecutionUi(
    val code: String,
    val codeSha256Short: String,
    val inputSource: String,
    val limits: String,
    val online: Boolean,
)

/**
 * One approval card (roadmap HXA-036): the full authorization summary of one exact,
 * one-time action. Field list per the task text: 来源、目标、scope、参数、风险、Safety
 * Profile、Provider/MCP ID、网络 origin/residence、数据类别、规则有效期、代码/命令、
 * 预期影响和 verifier.
 *
 * Invariants:
 * - The card offers EXACTLY the two actions in [ACTIONS] ("本次批准 / 拒绝") — no
 *   "模型帮我批准", no "此后全部允许", no permanent-allow (doc 02 section 8.1; ADR-0005).
 *   [ACTIONS] is the single source the UI renders from, so a future drift is a test
 *   failure, not a silent copy change.
 * - [profile] is the Safety Profile at REQUEST TIME — a trusted fact captured when the
 *   dispatch started. A later profile switch must not rewrite this card or its pending
 *   decision (roadmap HXA-036 test: 切换 Profile 不改变待审批决定).
 * - [arguments] is the FULL canonical argument JSON (doc 02 section 5.4: the current
 *   ToolCall's complete arguments are not character-truncated in the UI); it is the same
 *   canonical text the approval hash was computed over.
 * - Network fields are null when the call carries no egress facts; the UI renders an
 *   explicit "无出网" line — absence is displayed, never omitted silently.
 */
data class ApprovalCardUi(
    val approvalId: String,
    val bindingHash: String,
    val state: ApprovalCardState,
    val source: String,
    val target: String,
    val scope: String,
    val arguments: String,
    val risk: String,
    val profile: SafetyProfile,
    val providerMcpId: String?,
    val networkOrigin: String?,
    val residence: String?,
    val dataCategory: String,
    val boundedRule: BoundedRuleUi?,
    val codeOrCommand: String?,
    /** The code-execution section (full code block + input summary + limits + hash); HXA-053. */
    val codeExecution: CodeExecutionUi? = null,
    val expectedImpact: String,
    val verifier: String,
    val confirmationDetail: String,
    val terminalDetail: String?,
) {
    companion object {
        /** The ONLY actions a generic L2/L3 approval card may offer (roadmap HXA-036). */
        val ACTIONS: List<String> = listOf("本次批准", "拒绝")

        /** Rendered when the call carries no egress facts (explicit, not silent). */
        const val NO_EGRESS = "无出网"

        /** The code-execution input-source line when the call passes no `input` value. */
        const val NO_INPUT = "无输入"
    }
}

/**
 * Maps the execution-path facts onto [ApprovalCardUi] (roadmap HXA-036). Pure and
 * JVM-testable: every label rule (risk, target, source, category, state) lives here so the
 * rendered card and the tests share one implementation. One object on purpose: the
 * display mapping is a single surface — splitting it would fragment the card's contract.
 */
@Suppress("TooManyFunctions")
object ApprovalUiMapper {
    /** The verifier line: what the pipeline checks after execution (doc 02 section 7.1 变更后验证). */
    const val VERIFIER_TEXT = "输出必须通过注册的 outputSchema 校验；全量输出记录 SHA-256 哈希"

    /** 预期影响: the registered contract's description — what the tool says it does. */
    fun expectedImpact(description: String): String = description

    fun sourceLabel(
        isMcp: Boolean,
        serverId: String?,
    ): String = if (isMcp) "MCP 服务器：${serverId ?: "unknown"}" else "内置工具"

    fun providerMcpIdLabel(
        isMcp: Boolean,
        serverId: String?,
    ): String? = if (isMcp) serverId else null

    fun targetLabel(target: ExecutionTargetType): String =
        when (target) {
            ExecutionTargetType.LOCAL_ANDROID -> "本机（主应用进程）"
            ExecutionTargetType.LOCAL_QUICKJS -> "本机（隔离 QuickJS 进程）"
            ExecutionTargetType.LOCAL_PROOT -> "本机（PRoot Runtime 应用）"
            ExecutionTargetType.LOCAL_CLI_RUNTIME -> "本机（CLI Runtime 应用）"
        }

    /**
     * The risk line: the Policy Engine's DYNAMIC risk (what it actually decided with),
     * with the descriptor's base risk shown when they differ so the user sees the uplift.
     */
    fun riskLabel(
        base: RiskLevel,
        dynamic: RiskLevel,
    ): String = if (base == dynamic) riskLabel(dynamic) else "${riskLabel(base)} → 动态 ${riskLabel(dynamic)}"

    fun riskLabel(level: RiskLevel): String =
        when (level) {
            RiskLevel.L0 -> "L0（无风险）"
            RiskLevel.L1 -> "L1（低风险）"
            RiskLevel.L2 -> "L2（需逐次批准）"
            RiskLevel.L3 -> "L3（高风险）"
        }

    fun profileLabel(profile: SafetyProfile): String =
        when (profile) {
            SafetyProfile.STANDARD -> "Standard（默认）"
            SafetyProfile.ADVANCED -> "Advanced（增强，非完全访问）"
        }

    fun stateLabel(state: ApprovalCardState): String =
        when (state) {
            ApprovalCardState.PENDING -> "等待批准"
            ApprovalCardState.APPROVED -> "已批准，执行中"
            ApprovalCardState.DENIED -> "已拒绝"
            ApprovalCardState.SUCCEEDED -> "已执行成功"
            ApprovalCardState.FAILED -> "执行失败"
        }

    /** Stable user-visible labels for the 15 dispatch outcome codes (audit page + card).
     * The complexity is the exhaustive label table for the closed enum — one branch per
     * code, no logic (a new code without a label is a compile error). */
    @Suppress("CyclomaticComplexMethod")
    fun codeLabel(code: DispatchOutcomeCode): String =
        when (code) {
            DispatchOutcomeCode.UNKNOWN_TOOL -> "未注册工具"
            DispatchOutcomeCode.NO_IMPLEMENTATION -> "无已注册实现"
            DispatchOutcomeCode.INVALID_ARGUMENTS -> "参数不符合注册 Schema"
            DispatchOutcomeCode.POLICY_DENIED -> "策略拒绝"
            DispatchOutcomeCode.SAME_TURN_DENIED -> "本回合已拒绝该动作"
            DispatchOutcomeCode.APPROVAL_PENDING -> "审批仍待决定"
            DispatchOutcomeCode.APPROVAL_DENIED -> "用户拒绝审批"
            DispatchOutcomeCode.APPROVAL_EXPIRED -> "审批已过期"
            DispatchOutcomeCode.APPROVAL_CONSUMED -> "审批凭证已消费"
            DispatchOutcomeCode.APPROVAL_NOT_FOUND -> "审批记录不存在"
            DispatchOutcomeCode.CANCELLED_BEFORE_START -> "启动前已取消"
            DispatchOutcomeCode.SUCCESS -> "成功"
            DispatchOutcomeCode.TIMEOUT -> "执行超时"
            DispatchOutcomeCode.CANCELLED_AFTER_START -> "执行中取消（副作用未知）"
            DispatchOutcomeCode.TOOL_FAILED -> "工具执行失败"
            DispatchOutcomeCode.INVALID_OUTPUT -> "输出不符合注册 Schema"
        }

    fun sourceLabel(source: DecisionSource): String =
        when (source) {
            DecisionSource.POLICY -> "策略引擎"
            DecisionSource.USER -> "用户"
            DecisionSource.FRAMEWORK -> "框架"
        }

    /**
     * 代码/命令: fixed code/command tools carry their source as a string argument
     * (doc 02 section 7.1: 源码/命令属于参数). Extracted from the canonical argument
     * object under the well-known keys; null when the tool takes no code/command.
     */
    fun codeOrCommand(arguments: JsonObject): String? {
        val code = stringArg(arguments, "code")
        if (!code.isNullOrBlank()) return code
        val command = stringArg(arguments, "command")
        return command.takeIf { !it.isNullOrBlank() }
    }

    /**
     * The code-execution section (doc 03 §5) for a CODE_EXECUTION tool: the FULL code, its
     * SHA-256 short digest, the input SOURCE + size (not the body), the fixed "联网：否"
     * flag, and the applied §4.1 limits. Null for every other operation class and when there
     * is no `code` argument to show. Pure/JVM-testable like the rest of the mapper.
     */
    fun codeExecutionUi(
        descriptor: ToolDescriptor,
        arguments: JsonObject,
    ): CodeExecutionUi? {
        if (descriptor.operationClass != ToolOperationClass.CODE_EXECUTION) return null
        return stringArg(arguments, "code")?.takeIf { it.isNotBlank() }?.let { code ->
            CodeExecutionUi(
                code = code,
                codeSha256Short = sha256Short(code),
                inputSource = inputSource(arguments["input"]),
                limits = limitsLabel(),
                online = false,
            )
        }
    }

    /** The input-source line: an explicit "无输入" when absent, else the inline JSON size (not body). */
    private fun inputSource(input: kotlinx.serialization.json.JsonElement?): String =
        if (input == null) {
            ApprovalCardUi.NO_INPUT
        } else {
            "内联 JSON 值（${CanonicalArgs.canonicalize(input).toByteArray(StandardCharsets.UTF_8).size} 字节）"
        }

    /** The fixed §4.1 limits the QuickJS backend applies (the model cannot change them). */
    fun limitsLabel(): String {
        val l = JsExecutionLimits.DEFAULTS
        return "超时 ${l.timeoutMs / 1000L} s · 内存 ${l.memoryBytes / (1024L * 1024L)} MiB · " +
            "输出上限 ${l.maxOutputBytes / 1024L} KiB · 并发 1"
    }

    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun stringArg(
        arguments: JsonObject,
        key: String,
    ): String? = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 数据类别: the egress sensitivity when the call egresses, else the source-origin category. */
    fun categoryLabel(
        dataOrigin: DataOrigin,
        egressCategory: DataSensitivity?,
    ): String =
        if (egressCategory != null) {
            when (egressCategory) {
                DataSensitivity.NORMAL -> "普通内容"
                DataSensitivity.SENSITIVE -> "高敏内容（逐次确认）"
                DataSensitivity.FORBIDDEN -> "禁止类内容（不可发送）"
            }
        } else {
            when (dataOrigin) {
                DataOrigin.WORKSPACE -> "Workspace 数据"
                DataOrigin.SAF -> "SAF 文档树"
                DataOrigin.ALL_FILES -> "全文件数据"
                DataOrigin.BROWSER -> "浏览器页面内容"
                DataOrigin.ACCESSIBILITY -> "无障碍内容"
                DataOrigin.MCP -> "MCP 数据（默认不可信）"
                DataOrigin.ROOT -> "Root 数据"
                DataOrigin.LOCAL -> "本机数据"
                DataOrigin.NETWORK -> "网络数据"
            }
        }

    fun scopeLabel(scopeRef: String): String = if (scopeRef == "unscoped") "无作用域（unscoped）" else scopeRef

    /**
     * 高敏出网规则的卡片行 (roadmap HXA-036: 高敏出网规则单独标为有界 Policy 规则): the
     * live rule that already satisfies the card's egress is shown as a BOUNDED rule — its
     * exact binding (target / origin / category / scope) and validity window — never as a
     * general approval credential (ADR-0005). Null when the call has no covered rule.
     */
    fun boundedRuleUi(rule: HighSensitivityRule?): BoundedRuleUi? {
        if (rule == null) return null
        val target = rule.target
        val targetId =
            when (target) {
                is EgressTarget.Provider -> "provider:${target.id.value}"
                is EgressTarget.Mcp -> "mcp:${target.id.value}"
            }
        val windowEnd =
            rule.expiresAt
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val display =
            "origin ${rule.origin.origin} · ${rule.dataCategory.name} · " +
                "作用域 ${rule.scope.toScopeRef()} · 有效期至 $windowEnd（一次授权，非通用凭证）"
        return BoundedRuleUi(
            targetId = targetId,
            origin = rule.origin.origin,
            categories = rule.dataCategory.name,
            scope = rule.scope.toScopeRef(),
            expiresAt = rule.expiresAt.toEpochMilli(),
            display = display,
        )
    }

    /**
     * Builds the display card from the execution-path facts. The [arguments] are rendered
     * through [CanonicalArgs] — the SAME encoder the approval hash was computed over — so
     * what the user approves is byte-identical to what the binding hashes (doc 02
     * section 5.4: the full arguments, not truncated).
     */
    @Suppress("LongParameterList") // one parameter per mandated display fact (roadmap HXA-036 field list)
    fun buildCard(
        approvalId: String,
        binding: ApprovalBinding,
        state: ApprovalCardState,
        descriptor: ToolDescriptor,
        arguments: JsonObject,
        dynamicRisk: RiskLevel,
        profile: SafetyProfile,
        dataOrigin: DataOrigin,
        egressOrigin: String?,
        egressResidence: String?,
        egressCategory: DataSensitivity?,
        boundedRule: BoundedRuleUi?,
        confirmationDetail: String,
        terminalDetail: String?,
    ): ApprovalCardUi {
        val origin = descriptor.origin
        return ApprovalCardUi(
            approvalId = approvalId,
            bindingHash = binding.hash,
            state = state,
            source = sourceLabel(origin is ToolOrigin.McpOrigin, (origin as? ToolOrigin.McpOrigin)?.serverId),
            target = targetLabel(descriptor.executionTarget),
            scope = scopeLabel(binding.scopeRef),
            arguments = CanonicalArgs.canonicalize(arguments),
            risk = riskLabel(descriptor.baseRisk, dynamicRisk),
            profile = profile,
            providerMcpId =
                providerMcpIdLabel(origin is ToolOrigin.McpOrigin, (origin as? ToolOrigin.McpOrigin)?.serverId),
            networkOrigin = egressOrigin,
            residence = egressResidence,
            dataCategory = categoryLabel(dataOrigin, egressCategory),
            boundedRule = boundedRule,
            codeOrCommand = codeOrCommand(arguments),
            codeExecution = codeExecutionUi(descriptor, arguments),
            expectedImpact = expectedImpact(descriptor.description),
            verifier = VERIFIER_TEXT,
            confirmationDetail = confirmationDetail,
            terminalDetail = terminalDetail,
        )
    }
}
