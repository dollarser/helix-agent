package com.helix.app.approval

import com.helix.app.R
import com.helix.core.model.SafetyProfile
import com.helix.runtime.quickjs.JsExecutionLimits

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
