package com.helix.app.approval

import com.helix.app.R
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
 * A stable user-visible label produced by [ApprovalUiMapper] (HXA-069): a string-resource
 * ID plus its positional string arguments — NEVER locale text, so this pure, plain-JUnit-
 * tested mapper holds no [android.content.Context]. The Android UI resolves a label via
 * `stringResource(label.res, *label.args.toTypedArray())`.
 */
data class ApprovalLabel(
    val res: Int,
    val args: List<String> = emptyList(),
)

/**
 * The display of an ACTIVE bounded Policy rule (ADR-0005) that satisfied the egress this
 * card is about — roadmap HXA-036: 高敏出网规则单独标为有界 Policy 规则.
 *
 * [displayRes]/[displayArgs] always carry the "有界 Policy 规则" line and the expiry — a
 * bounded rule is NEVER shown as a general approval credential (it cannot authorize file
 * changes, shell, Root, Accessibility or a new origin — doc 02 section 8.1). The label is
 * a string-resource ID + args resolved by the UI (HXA-069).
 */
data class BoundedRuleUi(
    val targetId: String,
    val origin: String,
    val categories: String,
    val scope: String,
    val expiresAt: Long,
    val displayRes: Int,
    val displayArgs: List<String> = emptyList(),
)

/**
 * The code-execution section of a code-execution tool's approval card (HXA-053; doc 03 §5).
 * Renders the FULL code as a copyable/searchable block, the input SOURCE + size (never the
 * sensitive body by default), the FIXED "联网：否" line, the applied limits, and the code's
 * SHA-256 short digest. Present only for CODE_EXECUTION tools; null otherwise.
 *
 * The limits come from the fixed §4.1 defaults the backend enforces ([JsExecutionLimits]
 * DEFAULTS) — never from model arguments (the model cannot raise them). The user-visible
 * lines ([inputSourceRes], [limitsRes]) are string-resource IDs + args resolved by the UI
 * (HXA-069).
 */
data class CodeExecutionUi(
    val code: String,
    val codeSha256Short: String,
    val inputSourceRes: Int,
    val inputSourceArgs: List<String> = emptyList(),
    val limitsRes: Int,
    val limitsArgs: List<String> = emptyList(),
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
 * - User-visible label fields ([sourceRes], [targetRes], [riskRes], [dataCategoryRes],
 *   [verifierRes]) are STABLE string-resource IDs (+ args), never locale text (HXA-069):
 *   the UI resolves them to the current locale. [scope] keeps the STABLE scope ref; the
 *   UI localizes the "unscoped" ref.
 */
data class ApprovalCardUi(
    val approvalId: String,
    val bindingHash: String,
    val state: ApprovalCardState,
    val sourceRes: Int,
    val sourceArgs: List<String> = emptyList(),
    val targetRes: Int,
    val scope: String,
    val arguments: String,
    /** The risk line template; when the dynamic risk differs from the base, [riskArgs] holds the
     * string-resource IDs of the two level labels the template interpolates (empty otherwise).
     */
    val riskRes: Int,
    val riskArgs: List<Int> = emptyList(),
    val profile: SafetyProfile,
    val providerMcpId: String?,
    val networkOrigin: String?,
    val residence: String?,
    val dataCategoryRes: Int,
    val boundedRule: BoundedRuleUi?,
    val codeOrCommand: String?,
    /** The code-execution section (full code block + input summary + limits + hash); HXA-053. */
    val codeExecution: CodeExecutionUi? = null,
    val expectedImpact: String,
    val verifierRes: Int,
    val confirmationDetail: String,
    val terminalDetail: String?,
) {
    companion object {
        /** The ONLY actions a generic L2/L3 approval card may offer (roadmap HXA-036) —
         * string-resource IDs (HXA-069).
         */
        val ACTIONS: List<Int> = listOf(R.string.approval_action_approve_once, R.string.approval_action_deny)

        /** Rendered when the call carries no egress facts (explicit, not silent) — string-resource
         * ID (HXA-069).
         */
        val NO_EGRESS: Int = R.string.approval_no_egress

        /** The code-execution input-source line when the call passes no `input` value — string-
         * resource ID (HXA-069).
         */
        val NO_INPUT: Int = R.string.approval_no_input
    }
}

/**
 * Maps the execution-path facts onto [ApprovalCardUi] (roadmap HXA-036). Pure and
 * JVM-testable: every label rule (risk, target, source, category, state) lives here so the
 * rendered card and the tests share one implementation. One object on purpose: the
 * display mapping is a single surface — splitting it would fragment the card's contract.
 *
 * HXA-069: every user-visible label is emitted as a STABLE string-resource ID (+ args) —
 * never locale text — so this object holds no [android.content.Context]; the Android UI
 * resolves the IDs via `stringResource`.
 */
@Suppress("TooManyFunctions")
object ApprovalUiMapper {
    /** The verifier line: what the pipeline checks after execution (doc 02 section 7.1 变更后验证)
     * — a string-resource ID (HXA-069), resolved by the UI.
     */
    val VERIFIER_TEXT: Int = R.string.approval_verifier_text

    /** 预期影响: the registered contract's description — what the tool says it does (a stable,
     * developer-registered contract text — pass-through, not localized here).
     */
    fun expectedImpact(description: String): String = description

    /** 来源: the MCP server id (localized template + arg) or the built-in label. */
    fun sourceLabel(
        isMcp: Boolean,
        serverId: String?,
    ): ApprovalLabel =
        if (isMcp) {
            ApprovalLabel(R.string.approval_source_mcp, listOf(serverId ?: "unknown"))
        } else {
            ApprovalLabel(R.string.approval_source_builtin)
        }

    fun providerMcpIdLabel(
        isMcp: Boolean,
        serverId: String?,
    ): String? = if (isMcp) serverId else null

    /** 目标: the isolation boundary the tool runs in — a string-resource ID (HXA-069). */
    fun targetLabel(target: ExecutionTargetType): Int =
        when (target) {
            ExecutionTargetType.LOCAL_ANDROID -> R.string.approval_target_local_android
            ExecutionTargetType.LOCAL_QUICKJS -> R.string.approval_target_local_quickjs
            ExecutionTargetType.LOCAL_PROOT -> R.string.approval_target_local_proot
            ExecutionTargetType.LOCAL_CLI_RUNTIME -> R.string.approval_target_local_cli
        }

    /**
     * The risk line: the Policy Engine's DYNAMIC risk (what it actually decided with),
     * with the descriptor's base risk shown when they differ so the user sees the uplift.
     * Returns the template string-resource ID; when the dynamic risk differs from the
     * base, the second element holds the string-resource IDs of the two level labels the
     * template interpolates (HXA-069 — the UI resolves them and passes them as format
     * args).
     */
    fun riskLabel(
        base: RiskLevel,
        dynamic: RiskLevel,
    ): Pair<Int, List<Int>> =
        if (base == dynamic) {
            riskLabel(dynamic) to emptyList()
        } else {
            R.string.approval_risk_dynamic_upgrade to listOf(riskLabel(base), riskLabel(dynamic))
        }

    /** 风险等级: a string-resource ID (HXA-069). */
    fun riskLabel(level: RiskLevel): Int =
        when (level) {
            RiskLevel.L0 -> R.string.approval_risk_l0
            RiskLevel.L1 -> R.string.approval_risk_l1
            RiskLevel.L2 -> R.string.approval_risk_l2
            RiskLevel.L3 -> R.string.approval_risk_l3
        }

    /** Safety Profile label — a string-resource ID (HXA-069). */
    fun profileLabel(profile: SafetyProfile): Int =
        when (profile) {
            SafetyProfile.STANDARD -> R.string.approval_profile_standard
            SafetyProfile.ADVANCED -> R.string.approval_profile_advanced
        }

    /** Card state label — a string-resource ID (HXA-069). */
    fun stateLabel(state: ApprovalCardState): Int =
        when (state) {
            ApprovalCardState.PENDING -> R.string.approval_state_pending
            ApprovalCardState.APPROVED -> R.string.approval_state_approved
            ApprovalCardState.DENIED -> R.string.approval_state_denied
            ApprovalCardState.SUCCEEDED -> R.string.approval_state_succeeded
            ApprovalCardState.FAILED -> R.string.approval_state_failed
        }

    /** Stable user-visible labels for the dispatch outcome codes (audit page + card) —
     * string-resource IDs (HXA-069).
     * The complexity is the exhaustive label table for the closed enum — one branch per
     * code, no logic (a new code without a label is a compile error). */
    @Suppress("CyclomaticComplexMethod")
    fun codeLabel(code: DispatchOutcomeCode): Int =
        when (code) {
            DispatchOutcomeCode.UNKNOWN_TOOL -> R.string.approval_code_unknown_tool
            DispatchOutcomeCode.NO_IMPLEMENTATION -> R.string.approval_code_no_implementation
            DispatchOutcomeCode.INVALID_ARGUMENTS -> R.string.approval_code_invalid_arguments
            DispatchOutcomeCode.POLICY_DENIED -> R.string.approval_code_policy_denied
            DispatchOutcomeCode.SAME_TURN_DENIED -> R.string.approval_code_same_turn_denied
            DispatchOutcomeCode.APPROVAL_PENDING -> R.string.approval_code_approval_pending
            DispatchOutcomeCode.APPROVAL_DENIED -> R.string.approval_code_approval_denied
            DispatchOutcomeCode.APPROVAL_EXPIRED -> R.string.approval_code_approval_expired
            DispatchOutcomeCode.APPROVAL_CONSUMED -> R.string.approval_code_approval_consumed
            DispatchOutcomeCode.APPROVAL_NOT_FOUND -> R.string.approval_code_approval_not_found
            DispatchOutcomeCode.CANCELLED_BEFORE_START -> R.string.approval_code_cancelled_before_start
            DispatchOutcomeCode.SUCCESS -> R.string.approval_code_success
            DispatchOutcomeCode.TIMEOUT -> R.string.approval_code_timeout
            DispatchOutcomeCode.CANCELLED_AFTER_START -> R.string.approval_code_cancelled_after_start
            DispatchOutcomeCode.TOOL_FAILED -> R.string.approval_code_tool_failed
            DispatchOutcomeCode.INVALID_OUTPUT -> R.string.approval_code_invalid_output
        }

    /** 决策来源: a string-resource ID (HXA-069). */
    fun sourceLabel(source: DecisionSource): Int =
        when (source) {
            DecisionSource.POLICY -> R.string.approval_decision_source_policy
            DecisionSource.USER -> R.string.approval_decision_source_user
            DecisionSource.FRAMEWORK -> R.string.approval_decision_source_framework
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
            val input = inputSource(arguments["input"])
            val limits = limitsLabel()
            CodeExecutionUi(
                code = code,
                codeSha256Short = sha256Short(code),
                inputSourceRes = input.res,
                inputSourceArgs = input.args,
                limitsRes = limits.res,
                limitsArgs = limits.args,
                online = false,
            )
        }
    }

    /** The input-source line: an explicit "无输入" when absent, else the inline JSON size (not
     * body) — a string-resource ID + arg (HXA-069).
     */
    private fun inputSource(input: kotlinx.serialization.json.JsonElement?): ApprovalLabel =
        if (input == null) {
            ApprovalLabel(ApprovalCardUi.NO_INPUT)
        } else {
            ApprovalLabel(
                R.string.approval_input_inline_json,
                listOf(
                    CanonicalArgs
                        .canonicalize(input)
                        .toByteArray(StandardCharsets.UTF_8)
                        .size
                        .toString(),
                ),
            )
        }

    /** The fixed §4.1 limits the QuickJS backend applies (the model cannot change them) — a
     * string-resource ID + args (HXA-069).
     */
    fun limitsLabel(): ApprovalLabel {
        val l = JsExecutionLimits.DEFAULTS
        return ApprovalLabel(
            R.string.approval_limits_value,
            listOf(
                (l.timeoutMs / 1000L).toString(),
                (l.memoryBytes / (1024L * 1024L)).toString(),
                (l.maxOutputBytes / 1024L).toString(),
            ),
        )
    }

    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun stringArg(
        arguments: JsonObject,
        key: String,
    ): String? = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 数据类别: the egress sensitivity when the call egresses, else the source-origin category
     * — a string-resource ID (HXA-069).
     */
    fun categoryLabel(
        dataOrigin: DataOrigin,
        egressCategory: DataSensitivity?,
    ): Int =
        if (egressCategory != null) {
            when (egressCategory) {
                DataSensitivity.NORMAL -> R.string.approval_category_normal
                DataSensitivity.SENSITIVE -> R.string.approval_category_sensitive
                DataSensitivity.FORBIDDEN -> R.string.approval_category_forbidden
            }
        } else {
            when (dataOrigin) {
                DataOrigin.WORKSPACE -> R.string.approval_category_workspace
                DataOrigin.SAF -> R.string.approval_category_saf
                DataOrigin.ALL_FILES -> R.string.approval_category_all_files
                DataOrigin.BROWSER -> R.string.approval_category_browser
                DataOrigin.ACCESSIBILITY -> R.string.approval_category_accessibility
                DataOrigin.MCP -> R.string.approval_category_mcp
                DataOrigin.ROOT -> R.string.approval_category_root
                DataOrigin.LOCAL -> R.string.approval_category_local
                DataOrigin.NETWORK -> R.string.approval_category_network
            }
        }

    /**
     * 高敏出网规则的卡片行 (roadmap HXA-036: 高敏出网规则单独标为有界 Policy 规则): the
     * live rule that already satisfies the card's egress is shown as a BOUNDED rule — its
     * exact binding (target / origin / category / scope) and validity window — never as a
     * general approval credential (ADR-0005). Null when the call has no covered rule.
     * The display line is a string-resource ID + stable binding args (HXA-069).
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
        return BoundedRuleUi(
            targetId = targetId,
            origin = rule.origin.origin,
            categories = rule.dataCategory.name,
            scope = rule.scope.toScopeRef(),
            expiresAt = rule.expiresAt.toEpochMilli(),
            displayRes = R.string.approval_bounded_rule_display,
            displayArgs = listOf(rule.origin.origin, rule.dataCategory.name, rule.scope.toScopeRef(), windowEnd),
        )
    }

    /**
     * Builds the display card from the execution-path facts. The [arguments] are rendered
     * through [CanonicalArgs] — the SAME encoder the approval hash was computed over — so
     * what the user approves is byte-identical to what the binding hashes (doc 02
     * section 5.4: the full arguments, not truncated). Label fields are emitted as
     * string-resource IDs (+ args) for the UI to resolve (HXA-069); [scope] keeps the
     * STABLE scope ref (the UI localizes the "unscoped" ref).
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
        val source = sourceLabel(origin is ToolOrigin.McpOrigin, (origin as? ToolOrigin.McpOrigin)?.serverId)
        val (riskRes, riskArgs) = riskLabel(descriptor.baseRisk, dynamicRisk)
        return ApprovalCardUi(
            approvalId = approvalId,
            bindingHash = binding.hash,
            state = state,
            sourceRes = source.res,
            sourceArgs = source.args,
            targetRes = targetLabel(descriptor.executionTarget),
            scope = binding.scopeRef,
            arguments = CanonicalArgs.canonicalize(arguments),
            riskRes = riskRes,
            riskArgs = riskArgs,
            profile = profile,
            providerMcpId =
                providerMcpIdLabel(origin is ToolOrigin.McpOrigin, (origin as? ToolOrigin.McpOrigin)?.serverId),
            networkOrigin = egressOrigin,
            residence = egressResidence,
            dataCategoryRes = categoryLabel(dataOrigin, egressCategory),
            boundedRule = boundedRule,
            codeOrCommand = codeOrCommand(arguments),
            codeExecution = codeExecutionUi(descriptor, arguments),
            expectedImpact = expectedImpact(descriptor.description),
            verifierRes = VERIFIER_TEXT,
            confirmationDetail = confirmationDetail,
            terminalDetail = terminalDetail,
        )
    }
}
