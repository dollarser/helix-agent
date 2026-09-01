package com.helix.tools.framework

import com.helix.core.model.RiskLevel

/**
 * Stable per-dispatch audit codes (roadmap HXA-035 "audit"; doc 11 采纳矩阵:
 * "记录 queue/approval/execution/verification 时间、decision source，不记录敏感正文").
 * Every terminal dispatch outcome maps to exactly one code; codes are stable identifiers
 * for the audit page (HXA-036) and for tests — never free text.
 */
enum class DispatchOutcomeCode {
    // before anything ran
    UNKNOWN_TOOL,
    NO_IMPLEMENTATION,
    INVALID_ARGUMENTS,
    POLICY_DENIED,
    SAME_TURN_DENIED,
    APPROVAL_PENDING,
    APPROVAL_DENIED,
    APPROVAL_EXPIRED,
    APPROVAL_CONSUMED,
    APPROVAL_NOT_FOUND,
    CANCELLED_BEFORE_START,

    // execution started
    SUCCESS,
    TIMEOUT,
    CANCELLED_AFTER_START,
    TOOL_FAILED,
    INVALID_OUTPUT,
}

/** Who made the terminal decision for this dispatch (doc 11: Policy/User/Recovery sources). */
enum class DecisionSource {
    /** The policy engine (default denials, risk, egress gate) decided. */
    POLICY,

    /** The user decided (approval granted or denied at the confirmation surface). */
    USER,

    /** The framework decided (validation, storage state such as pending/expired/consumed,
     * timeout, cancellation, executor/contract failure).
     */
    FRAMEWORK,
}

/**
 * The audit record for ONE dispatch — the only event the dispatcher emits, so a dispatch
 * is reconstructable from a single row (doc 11: model-visible ⇔ persisted; doc 02 §9.1
 * `audit_events`).
 *
 * Redaction: the event carries NO argument or output BODY — only hashes (the approval
 * binding hash, the action fingerprint, the output hash) and bounded metadata. Timestamps
 * are epoch milliseconds from the injected clock; stage timestamps are null until the
 * stage was reached (e.g. `approvalAcquiredAt` is null when the policy allowed the call).
 *
 * [riskLevel] is the Policy Engine's DYNAMIC risk for this dispatch (base risk plus the
 * egress/change factors) — the value the audit page (HXA-036) filters by. It is null when
 * the dispatch stopped before the policy stage ran (validation, unknown tool).
 */
data class DispatchAuditEvent(
    val correlationId: String,
    val turnId: String,
    val sessionId: String,
    val toolName: String,
    val toolVersion: String,
    /** When the ToolScheduler enqueued the call (null for a direct dispatch). */
    val queuedAt: Long? = null,
    val startedAt: Long,
    val policyDecidedAt: Long?,
    val approvalAcquiredAt: Long?,
    val executionStartedAt: Long?,
    val finishedAt: Long,
    val code: DispatchOutcomeCode,
    val decisionSource: DecisionSource,
    val riskLevel: RiskLevel?,
    val bindingHash: String?,
    val actionFingerprint: String?,
    val outputHash: String?,
    val outputTruncated: Boolean,
    /** 1-based attempt number within the dispatch (doc 11 section 3.3). */
    val attemptId: Int = 1,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(toolName.isNotBlank()) { "toolName must not be blank" }
        require(finishedAt >= startedAt) { "finishedAt must not precede startedAt" }
        require(queuedAt == null || startedAt >= queuedAt) { "startedAt must not precede queuedAt" }
        require(attemptId >= 1) { "attemptId must be >= 1" }
        require(
            policyDecidedAt == null || policyDecidedAt >= startedAt,
        ) { "policyDecidedAt must not precede startedAt" }
        require(approvalAcquiredAt == null || approvalAcquiredAt >= startedAt) {
            "approvalAcquiredAt must not precede startedAt"
        }
        require(executionStartedAt == null || executionStartedAt >= startedAt) {
            "executionStartedAt must not precede startedAt"
        }
    }
}

/**
 * The dispatcher's audit sink (doc 02 §9.1 `audit_events`). The dispatcher treats it as a
 * fail-closed dependency: a sink that throws propagates (a dispatch that cannot be audited
 * is not a successful dispatch — AGENTS: no catch-all success). The storage-backed
 * implementation lands with the audit page (HXA-036).
 */
interface AuditSink {
    fun record(event: DispatchAuditEvent)
}
