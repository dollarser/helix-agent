package com.helix.tools.framework

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.Hex
import com.helix.core.model.McpServerId
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.Sha256
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.EgressRequest
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.MintRejectionCode
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.policy.PolicyDecision
import com.helix.core.policy.PolicyEngine
import com.helix.core.policy.PolicyInput
import com.helix.core.policy.ToolCallSource
import com.helix.core.policy.UserScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * The trusted dispatch request (roadmap HXA-035): one model-requested tool call plus the
 * per-call trusted facts the pipeline needs.
 *
 * The identity fields ([toolCallId], [turnId], [sessionId], [toolName], [toolVersion]) and
 * the PolicyInput contract fields (mode, profile, scope, data origin, execution target,
 * egress facet, change flags, LAN scopes) are produced by the trusted execution path — the
 * agent loop and the app — never by tool arguments. [uiToken] binds the presenting
 * approval surface into the approval hash (HXA-034). [args] is the raw model argument
 * object; validation and canonical hashing happen in the dispatcher.
 */
@Suppress("LongParameterList") // one field per PolicyInput contract fact + call identity + approval/UI binding
data class ToolDispatchRequest(
    val toolCallId: String,
    val turnId: String,
    val sessionId: String,
    val toolName: ToolName,
    val toolVersion: ToolVersion,
    val args: JsonObject,
    val mode: AgentMode,
    val profile: SafetyProfile,
    val executionTarget: ExecutionTargetType,
    val dataOrigin: DataOrigin,
    val scope: UserScope?,
    val uiToken: String,
    val egress: EgressRequest? = null,
    val originSeenInSession: Boolean = true,
    val lanScopes: Set<NetworkOriginScope> = emptySet(),
    val overwritesExisting: Boolean = false,
    val codeOrCommandChanged: Boolean = false,
    val sourceBindingChanged: Boolean = false,
    val cancel: CancelSignal = NoCancellation,
    /**
     * SCHEDULER metadata (roadmap HXA-037; doc 11 section 3.2): the moment the
     * ToolScheduler enqueued this call, epoch millis. Trusted platform facts — the model
     * cannot influence them (they arrive only via the app's scheduler). Null for a direct
     * (non-scheduled) dispatch. Audit-only: never part of the binding hash.
     */
    val queuedAt: Long? = null,
    /**
     * SCHEDULER metadata (roadmap HXA-037; doc 11 section 3.3): the hard attempt cap for
     * this call. 1 (default) = exactly one attempt (HXA-035 behavior). 2 = one bounded
     * technical retry, allowed ONLY after a confirmed zero-side-effect execution failure
     * and only for the identical envelope — any material change means a NEW ToolCall with
     * a new approval, never a retry.
     */
    val maxAttempts: Int = 1,
) {
    init {
        require(maxAttempts in 1..MAX_ATTEMPTS_HARD_CAP) {
            "maxAttempts must be in 1..$MAX_ATTEMPTS_HARD_CAP: $maxAttempts"
        }
    }
}

/** Hard attempt cap for one ToolCall (doc 11 section 3.3: 重试必须有稳定理由和硬上限). */
const val MAX_ATTEMPTS_HARD_CAP = 2

/**
 * The model-visible, bounded result of a successful dispatch: canonical output text
 * truncated to the descriptor's [ToolDescriptor.maxOutputBytes] with the SHA-256 of the
 * FULL (pre-truncation) output preserved — a truncated result still proves which output it
 * was (security doc section 7.3: 超限截断并保留 hash/Artifact 引用).
 */
data class BoundToolResult(
    val payload: String,
    val outputHash: Sha256,
    val truncated: Boolean,
    val executionMillis: Long,
) {
    init {
        require(executionMillis >= 0) { "executionMillis must not be negative" }
    }
}

/** The terminal outcome of one dispatch; exactly one of the four. */
sealed interface ToolDispatchOutcome {
    /** The call executed to a model-visible, bounded, output-schema-valid result. */
    data class Succeeded(
        val result: BoundToolResult,
    ) : ToolDispatchOutcome

    /** Nothing executed: rejected before execution (validation, policy or approval). */
    data class Denied(
        val code: DispatchOutcomeCode,
        val detail: String,
    ) : ToolDispatchOutcome

    /** Cancelled before start: zero side effects, no proof consumed, nothing executed. */
    data object Cancelled : ToolDispatchOutcome

    /**
     * Execution started and ended non-successfully with a stable error code.
     * [sideEffectFree] is the executor's CONFIRMED report that this attempt produced no
     * side effect (doc 11 section 3.3) — the only case a bounded technical retry is
     * allowed. The framework trusts this flag only because it is set by the platform
     * executor (never by the model or MCP); when in doubt it must stay false.
     */
    data class ExecutionFailed(
        val code: DispatchOutcomeCode,
        val detail: String,
        val sideEffectFree: Boolean = false,
    ) : ToolDispatchOutcome
}

/**
 * The tool dispatcher (roadmap HXA-035; architecture doc section 5.3/7.1): the SINGLE
 * entry point between model-requested tool calls and tool implementations (doc 11:
 * "Dispatcher 唯一入口；target 在 approval hash 中，失败不回退到低隔离执行域").
 *
 * Pipeline per dispatch:
 * 1. **validate** — resolve the registered contract (unregistered tool/version is a stable
 *    rejection), resolve the registered implementation, validate the arguments against the
 *    registered input schema (ALL violations reported, fail closed);
 * 2. **capability** — live Capability Center check of the descriptor's
 *    `requiredCapabilities`; missing capabilities feed the policy engine's default denial;
 * 3. **policy** — the Policy Engine over the trusted fact set (HXA-033);
 * 4. **approval** — only for `RequiresApproval`: build the [ApprovalBinding] from trusted
 *    facts only, check the same-turn denial set (below), acquire a typed [ApprovalProof]
 *    through the [ApprovalBroker] — a consumable credential, nothing else;
 * 5. **timeout/cancel** — a cancel signal set before start stops the dispatch with zero
 *    side effects; the deadline is the descriptor's hard timeout passed to the executor;
 * 6. **execute** — the registered implementation; the approval proof, if any, is consumed
 *    exactly when execution STARTS (the authorization is spent the moment the effect
 *    begins; a cancelled-before-start call never spends it);
 * 7. **bound result / verify** — the output must validate against the registered output
 *    schema (a tool violating its own contract is a stable error; its output never reaches
 *    the model), then canonicalized, hashed and truncated to the byte cap;
 * 8. **audit** — exactly one [DispatchAuditEvent] per ATTEMPT (no bodies, only hashes,
 *    stage timestamps, the decision source and the attemptId; a retried call therefore
 *    has one row per attempt, doc 11 section 3.3).
 *
 * Same-turn denial invariant (roadmap HXA-035 / security doc section 7.3): when the user
 * DENIES an approval, the dispatcher records the ACTION FINGERPRINT (the binding hash with
 * the call-instance id and the UI token removed — [actionFingerprint]) for that turn. A
 * later dispatch in the same turn whose fingerprint matches is rejected with
 * [DispatchOutcomeCode.SAME_TURN_DENIED] WITHOUT asking the user again — the exact same
 * high-risk action is not re-requested. A material change to arguments or scope changes
 * the fingerprint and therefore may generate a new approval. The set is cleared by
 * [endTurn] and bounded ([MAX_TRACKED_TURNS], least-recently-used eviction).
 *
 * Fail closed: a throwing [AuditSink] or [CapabilityCenter] propagates — a dispatch that
 * cannot be audited or capability-checked is not a successful dispatch (AGENTS.md).
 */
@Suppress("TooManyFunctions") // one method per pipeline stage; splitting fragments the security pipeline
class ToolDispatcher(
    private val clock: Clock,
    private val registry: ToolRegistry,
    private val implementations: ToolImplementationRegistry,
    private val capabilityCenter: CapabilityCenter,
    private val policyEngine: PolicyEngine,
    private val approvals: ApprovalBroker,
    private val audit: AuditSink,
    private val ruleProvider: () -> Set<HighSensitivityRule> = { emptySet() },
) {
    private val deniedLock = Any()
    private val deniedByTurn: LinkedHashMap<String, MutableSet<String>> = LinkedHashMap(16, 0.75f, true)

    /**
     * The single entry point; see the class KDoc for the pipeline.
     *
     * Bounded technical retry (roadmap HXA-037; doc 11 section 3.3): when an attempt
     * fails with a CONFIRMED zero-side-effect [ToolDispatchOutcome.ExecutionFailed] and
     * [ToolDispatchRequest.maxAttempts] allows it, the dispatcher re-runs the FULL
     * pre-approval pipeline (validate → capability → policy are re-checked live — a
     * revoked capability or a policy change stops the retry) and, if the approval is still
     * required, re-mints the proof from the SAME typed APPROVED record (no new card, no
     * new question to the user). Any other failure is terminal. Every attempt emits its
     * own audit event with its [DispatchAuditEvent.attemptId].
     *
     * Both catches below are deliberately broad: the dispatcher's contract is that ANY
     * unexpected stage/dependency throw (not just one declared type) still settles the
     * attempt durably before propagating, and a throwing audit sink must not lose the
     * original root cause. Catching less would let an undeclared type escape unaudited.
     */
    @Suppress("TooGenericExceptionCaught")
    fun dispatch(request: ToolDispatchRequest): ToolDispatchOutcome {
        var attempt = 0
        var carriedProof: ApprovalProof? = null
        // The re-minted retry reuses the SAME binding (same record): its audit rows must
        // carry the same binding hash and action fingerprint as the attempt that minted
        // them, even though the retry never presents the card again.
        var carriedBindingHash: String? = null
        var carriedActionFingerprint: String? = null
        while (true) {
            attempt += 1
            val startedAt = clock.now()
            val ctx = DispatchContext()
            ctx.attemptId = attempt
            if (carriedProof != null) {
                ctx.bindingHash = carriedBindingHash
                ctx.actionFingerprint = carriedActionFingerprint
            }
            // Contract: EXACTLY ONE audit event per attempt (HXA-035 stage 8). An unexpected
            // throw from a stage or dependency (executor NPE, broker/center ISE) must still
            // settle that attempt durably before propagating — a dispatch that cannot be
            // audited is not a silent success, and the caller (scheduler batch) settles the
            // slot from the thrown error. No retry: an unexpected throw means the side-effect
            // state is UNKNOWN, which is the one case a technical retry is forbidden.
            val outcome =
                try {
                    runAttempt(request, startedAt, ctx, carriedProof)
                } catch (t: Throwable) {
                    // Settle the attempt durably (the audit event) BEFORE rethrowing. The
                    // honest outcome depends on WHY the stage threw:
                    // - the turn was stopped while the stage was blocked (the broker's
                    //   approval wait throws its cancel exception; the cancel gate would
                    //   have returned Cancelled had it run later) -> Cancelled, the SAME
                    //   outcome the caller settles durably — audit and settlement agree
                    //   ("cancelled before start, no side effects");
                    // - anything else (executor NPE, dependency ISE) -> TOOL_FAILED with
                    //   unknown side-effect state.
                    // Neither case retries: the turn is being torn down, or the side-effect
                    // state is UNKNOWN (the one case a technical retry is forbidden).
                    val turnStoppedBeforeExecution =
                        request.cancel.isCancelled() && ctx.executionStartedAt == null
                    ctx.stopped =
                        DispatchContext.StopResult(
                            if (turnStoppedBeforeExecution) {
                                ToolDispatchOutcome.Cancelled
                            } else {
                                ToolDispatchOutcome.ExecutionFailed(
                                    DispatchOutcomeCode.TOOL_FAILED,
                                    "unexpected dispatch failure: ${t::class.simpleName} — ${t.message}",
                                )
                            },
                            DecisionSource.FRAMEWORK,
                        )
                    try {
                        finishStop(request, startedAt, ctx)
                    } catch (auditFailure: Throwable) {
                        // Fail closed (the audit failure still propagates) but do not LOSE
                        // the original root cause: keep it as a suppressed exception.
                        auditFailure.addSuppressed(t)
                        throw auditFailure
                    }
                    throw t
                }
            // The proof THIS attempt acquired (set by the approval stage, which runs
            // INSIDE the attempt): the retry refunds exactly the proof the attempt spent.
            val spent = ctx.attemptProof
            if (
                outcome is ToolDispatchOutcome.ExecutionFailed &&
                outcome.sideEffectFree &&
                attempt < request.maxAttempts
            ) {
                // Re-mint from the same APPROVED record when the attempt spent a proof;
                // approval-free calls simply re-run. A record that can no longer mint
                // (window elapsed) ends the retry with the original failure.
                val reminted = spent?.let { approvals.reMint(it) }
                if (spent != null && reminted == null) {
                    return outcome
                }
                reminted?.let { carriedProof = it }
                carriedBindingHash = ctx.bindingHash
                carriedActionFingerprint = ctx.actionFingerprint
            } else {
                return outcome
            }
        }
    }

    /**
     * One attempt: stages 1-7. Returns the settled outcome (its single audit event already
     * emitted inside the stage's finish). An unexpected throw from any stage/dependency
     * propagates — the caller in [dispatch] settles the audit before rethrowing.
     */
    private fun runAttempt(
        request: ToolDispatchRequest,
        startedAt: Instant,
        ctx: DispatchContext,
        carriedProof: ApprovalProof?,
    ): ToolDispatchOutcome {
        val descriptor = validateStage(request, ctx)
        val proof =
            if (descriptor != null) policyStage(request, descriptor, ctx, carriedProof) else null
        return when {
            descriptor == null || ctx.stopped != null -> finishStop(request, startedAt, ctx)
            else -> executeStage(request, proof, ctx, startedAt)
        }
    }

    /**
     * Stages 2-4: capability (live check), policy, approval. Returns the proof to spend at
     * execution start, or null for an allowed call. On a stop the outcome is recorded on
     * [ctx] and null is returned as well; [dispatch] distinguishes the two via
     * [DispatchContext.stopped].
     *
     * [carriedProof] (bounded technical retry, doc 11 section 3.3): when a previous attempt
     * of the SAME call was refunded and re-minted, the re-minted proof is reused here —
     * the confirmation surface is NOT presented again and no new record is created. The
     * live capability/policy checks still run: a revoked capability or a policy change
     * stops the retry. If the policy no longer requires approval the carried proof goes
     * unspent (it expires with its record; it is never consumed).
     */
    private fun policyStage(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
        ctx: DispatchContext,
        carriedProof: ApprovalProof?,
    ): ApprovalProof? {
        val evaluation = capabilityCenter.evaluate(descriptor.requiredCapabilities)
        val policy =
            policyEngine.evaluate(
                buildInput(request, descriptor, evaluation.missing.toSet()),
                ruleProvider(),
            )
        ctx.policyDecidedAt = clock.now().toEpochMilli()
        ctx.riskLevel = policy.dynamicRisk
        return when (val decision = policy.decision) {
            is PolicyDecision.Deny -> {
                stopped(
                    ctx,
                    ToolDispatchOutcome.Denied(
                        DispatchOutcomeCode.POLICY_DENIED,
                        "policy ${decision.code.name}: ${decision.detail}",
                    ),
                    DecisionSource.POLICY,
                )
            }

            PolicyDecision.Allow -> {
                null
            }

            is PolicyDecision.RequiresApproval -> {
                if (carriedProof != null) {
                    ctx.approvalAcquiredAt = clock.now().toEpochMilli()
                    ctx.attemptProof = carriedProof
                    carriedProof
                } else {
                    acquireApproval(request, descriptor, decision, ctx, policy.matchedEgressRule)
                }
            }
        }
    }

    /** Clears the same-turn denial set for [turnId] (the agent loop calls this at turn end). */
    fun endTurn(turnId: String) {
        synchronized(deniedLock) {
            deniedByTurn.remove(turnId)
        }
    }

    /**
     * The action identity for the same-turn denial invariant: the SHA-256 of the
     * [ApprovalBinding.canonicalJson] with the call-instance id and the UI token removed.
     * The approval PROOF still binds the exact call (including toolCallId and uiToken);
     * only the "is this the same action the user just refused" check is instance-blind.
     */
    internal fun actionFingerprint(binding: ApprovalBinding): String =
        sha256Hex(binding.copy(toolCallId = ACTION_IDENTITY, uiToken = ACTION_IDENTITY).canonicalJson)

    /**
     * Stage 1: resolve the registered contract, the registered implementation and validate
     * the arguments. Returns the descriptor on success; on failure records
     * [DispatchContext.stopped] and returns null. `IllegalArgumentException` is the
     * registry's exact "unknown (name, version)" signal: mapping that specific exception
     * from that specific call to a stable rejection code is the stage's job, and the
     * exception text is deliberately not forwarded — the stable code plus the request
     * identity is the audit content. Any other failure propagates: a broken registry is
     * the fail-closed path documented on the class, never a silent success.
     */
    private fun validateStage(
        request: ToolDispatchRequest,
        ctx: DispatchContext,
    ): ToolDescriptor? {
        val descriptor = orNull { registry.resolve(request.toolName, request.toolVersion) }
        val executor =
            if (descriptor != null) {
                orNull { implementations.resolve(request.toolName, request.toolVersion) }
            } else {
                null
            }
        val validation: ToolSchemaValidation =
            descriptor?.let { ToolSchemaValidator.validate(it.inputSchema, request.args) }
                ?: ToolSchemaValidation.Valid
        ctx.descriptor = descriptor
        ctx.executor = executor
        return when {
            descriptor == null -> {
                stopped(
                    ctx,
                    ToolDispatchOutcome.Denied(
                        DispatchOutcomeCode.UNKNOWN_TOOL,
                        "unregistered tool: ${request.toolName.value} v${request.toolVersion.value}",
                    ),
                    DecisionSource.FRAMEWORK,
                )
            }

            executor == null -> {
                stopped(
                    ctx,
                    ToolDispatchOutcome.Denied(
                        DispatchOutcomeCode.NO_IMPLEMENTATION,
                        "no registered implementation: ${request.toolName.value} v${request.toolVersion.value}",
                    ),
                    DecisionSource.FRAMEWORK,
                )
            }

            validation is ToolSchemaValidation.Invalid -> {
                stopped(
                    ctx,
                    ToolDispatchOutcome.Denied(
                        DispatchOutcomeCode.INVALID_ARGUMENTS,
                        "arguments violate the registered schema: ${validation.reasons.joinToString("; ")}",
                    ),
                    DecisionSource.FRAMEWORK,
                )
            }

            else -> {
                descriptor
            }
        }
    }

    /**
     * A registry (name, version) lookup that turns the "unknown" signal into null.
     * `IllegalArgumentException` is exactly the signal both registries throw for an
     * unknown (name, version) pair — swallowing ONLY that type (with no text) is what
     * maps it to a stable rejection code downstream; every other exception propagates.
     */
    @Suppress("SwallowedException") // intentional: the registry "unknown" signal -> null, text dropped on purpose
    private fun <T> orNull(block: () -> T): T? =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Stage 4: same-turn check + typed acquisition; null (with [DispatchContext.stopped]) on stop. */
    private fun acquireApproval(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
        decision: PolicyDecision.RequiresApproval,
        ctx: DispatchContext,
        matchedEgressRule: HighSensitivityRule?,
    ): ApprovalProof? {
        val binding = buildBinding(request, descriptor)
        ctx.bindingHash = binding.hash
        val fingerprint = actionFingerprint(binding)
        ctx.actionFingerprint = fingerprint
        if (fingerprint in deniedFor(request.turnId)) {
            return stopped(
                ctx,
                ToolDispatchOutcome.Denied(
                    DispatchOutcomeCode.SAME_TURN_DENIED,
                    "already denied this exact action in this turn; materially new arguments or scope required",
                ),
                DecisionSource.USER,
            )
        }
        val acquisition =
            approvals.acquire(
                ApprovalRequest(
                    binding,
                    decision.detail,
                    ctx.riskLevel ?: error("dynamic risk unset before the approval stage"),
                    request.cancel,
                    matchedEgressRule,
                ),
            )
        ctx.approvalAcquiredAt = clock.now().toEpochMilli()
        return when (acquisition) {
            is ApprovalAcquisition.Approved -> {
                ctx.attemptProof = acquisition.proof
                acquisition.proof
            }

            is ApprovalAcquisition.Rejected -> {
                // A record the user DECIDED DENIED is a user-side stop for the SAME action
                // identity: record the fingerprint so the exact action is not re-presented
                // in this turn (HXA-035 invariant — the broker may surface this as
                // Rejected(DENIED) from its mint chain, not only as the dedicated Denied
                // object). The other codes are per-RECORD storage states (consumed,
                // not-found, pending, expired window) and say nothing about the action:
                // a materially changed action (new fingerprint) must still be approvable.
                // The bounded technical retry (doc 11 section 3.3) rides a REFUND, not a
                // re-mint: the spent proof is refunded and re-minted from the same record
                // without presenting the card again, so a retried attempt must never be
                // treated as a user denial here.
                if (acquisition.code == MintRejectionCode.DENIED) {
                    markDenied(request.turnId, fingerprint)
                }
                stopped(
                    ctx,
                    ToolDispatchOutcome.Denied(
                        rejectionCode(acquisition.code),
                        "approval not consumable: ${acquisition.code.name.lowercase()}",
                    ),
                    if (acquisition.code == MintRejectionCode.DENIED) DecisionSource.USER else DecisionSource.FRAMEWORK,
                )
            }

            ApprovalAcquisition.Denied -> {
                markDenied(request.turnId, fingerprint)
                stopped(
                    ctx,
                    ToolDispatchOutcome.Denied(
                        DispatchOutcomeCode.APPROVAL_DENIED,
                        "the user denied this exact action",
                    ),
                    DecisionSource.USER,
                )
            }
        }
    }

    /** Stages 5-6: cancel gate, then execution — spending the proof exactly when it starts. */
    private fun executeStage(
        request: ToolDispatchRequest,
        proof: ApprovalProof?,
        ctx: DispatchContext,
        startedAt: Instant,
    ): ToolDispatchOutcome {
        val descriptor = ctx.descriptor ?: error("executeStage reached before validate")
        val executor = ctx.executor ?: error("executeStage reached before validate")
        if (request.cancel.isCancelled()) {
            return finish(request, startedAt, ctx, ToolDispatchOutcome.Cancelled, DecisionSource.FRAMEWORK)
        }
        val execStart = clock.now()
        ctx.executionStartedAt = execStart.toEpochMilli()
        if (proof != null) {
            approvals.consume(proof)
        }
        val call = buildCall(request, descriptor, execStart)
        val result = executor.execute(call)
        return when (result) {
            is ToolExecutorResult.Completed -> {
                bindOutput(result.output, descriptor, execStart, ctx)?.let { bound ->
                    ctx.outputHash = bound.outputHash.hex
                    ctx.outputTruncated = bound.truncated
                    finish(request, startedAt, ctx, ToolDispatchOutcome.Succeeded(bound), sourceOf(proof))
                } ?: finishStop(request, startedAt, ctx)
            }

            else -> {
                finishExecutionFailure(request, startedAt, ctx, result)
            }
        }
    }

    /** The non-completed executor outcomes: stable codes, side-effect state as the executor confirms it. */
    private fun finishExecutionFailure(
        request: ToolDispatchRequest,
        startedAt: Instant,
        ctx: DispatchContext,
        result: ToolExecutorResult,
    ): ToolDispatchOutcome {
        val failure =
            when (result) {
                ToolExecutorResult.TimedOut -> {
                    ToolDispatchOutcome.ExecutionFailed(
                        DispatchOutcomeCode.TIMEOUT,
                        "tool exceeded its deadline; the stable timeout error is the model-visible outcome",
                    )
                }

                ToolExecutorResult.Cancelled -> {
                    ToolDispatchOutcome.ExecutionFailed(
                        DispatchOutcomeCode.CANCELLED_AFTER_START,
                        "cancellation fired after execution started; side-effect state is unknown",
                    )
                }

                is ToolExecutorResult.Failed -> {
                    ToolDispatchOutcome.ExecutionFailed(
                        DispatchOutcomeCode.TOOL_FAILED,
                        result.detail,
                        result.sideEffectFree,
                    )
                }

                else -> {
                    error("unhandled executor result: $result")
                }
            }
        return finish(request, startedAt, ctx, failure, DecisionSource.FRAMEWORK)
    }

    /** The executor-facing call: hard deadline = descriptor timeout; the request's cancel signal passes through. */
    private fun buildCall(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
        execStart: Instant,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = request.toolCallId,
            toolName = request.toolName.value,
            toolVersion = request.toolVersion.value.toString(),
            args = request.args,
            executionTarget = request.executionTarget,
            deadline = Instant.ofEpochMilli(execStart.toEpochMilli() + descriptor.timeout.inWholeMilliseconds),
            cancel = request.cancel,
        )

    /** Stage 7: output schema verification + canonical hash + byte-cap truncation. */
    private fun bindOutput(
        output: JsonElement,
        descriptor: ToolDescriptor,
        execStart: Instant,
        ctx: DispatchContext,
    ): BoundToolResult? {
        val validation = ToolSchemaValidator.validate(descriptor.outputSchema, output)
        if (validation is ToolSchemaValidation.Invalid) {
            return stopped(
                ctx,
                ToolDispatchOutcome.ExecutionFailed(
                    DispatchOutcomeCode.INVALID_OUTPUT,
                    "tool output violates the registered output schema: ${validation.reasons.joinToString("; ")}",
                ),
                DecisionSource.FRAMEWORK,
            )
        }
        val canonical = CanonicalArgs.canonicalize(output)
        val fullHash = Sha256(sha256Hex(canonical))
        val maxBytes = descriptor.maxOutputBytes.toInt()
        var used = 0
        var end = 0
        while (end < canonical.length && used < maxBytes) {
            used += charUtf8Length(canonical[end])
            if (used > maxBytes) break
            end++
        }
        val truncated = end < canonical.length
        val payload = canonical.substring(0, end)
        val millis = Duration.between(execStart, clock.now()).toMillis()
        return BoundToolResult(payload, fullHash, truncated, millis)
    }

    /** Finishes a dispatch that a stage stopped: emits the audit event and returns the stop outcome. */
    private fun finishStop(
        request: ToolDispatchRequest,
        startedAt: Instant,
        ctx: DispatchContext,
    ): ToolDispatchOutcome {
        val stop = ctx.stopped ?: error("stage stopped without recording an outcome")
        return finish(request, startedAt, ctx, stop.outcome, stop.source)
    }

    /**
     * Records a terminal stop on the per-dispatch context and returns null (stage stop).
     * Generic in the stage's return type so `return stopped(ctx, ...)` compiles for
     * ToolDescriptor?, ApprovalProof? and BoundToolResult? alike.
     */
    private fun <T> stopped(
        ctx: DispatchContext,
        outcome: ToolDispatchOutcome,
        source: DecisionSource,
    ): T? {
        ctx.stopped = DispatchContext.StopResult(outcome, source)
        return null
    }

    /** Stage 8: emit the single audit event and return the outcome. */
    private fun finish(
        request: ToolDispatchRequest,
        startedAt: Instant,
        ctx: DispatchContext,
        outcome: ToolDispatchOutcome,
        source: DecisionSource,
    ): ToolDispatchOutcome {
        val code =
            when (outcome) {
                is ToolDispatchOutcome.Denied -> outcome.code
                is ToolDispatchOutcome.ExecutionFailed -> outcome.code
                ToolDispatchOutcome.Cancelled -> DispatchOutcomeCode.CANCELLED_BEFORE_START
                is ToolDispatchOutcome.Succeeded -> DispatchOutcomeCode.SUCCESS
            }
        audit.record(
            DispatchAuditEvent(
                correlationId = request.toolCallId,
                turnId = request.turnId,
                sessionId = request.sessionId,
                toolName = request.toolName.value,
                toolVersion = request.toolVersion.value.toString(),
                queuedAt = request.queuedAt,
                startedAt = startedAt.toEpochMilli(),
                policyDecidedAt = ctx.policyDecidedAt,
                approvalAcquiredAt = ctx.approvalAcquiredAt,
                executionStartedAt = ctx.executionStartedAt,
                finishedAt = clock.now().toEpochMilli(),
                code = code,
                decisionSource = source,
                riskLevel = ctx.riskLevel,
                bindingHash = ctx.bindingHash,
                actionFingerprint = ctx.actionFingerprint,
                outputHash = ctx.outputHash,
                outputTruncated = ctx.outputTruncated,
                attemptId = ctx.attemptId,
            ),
        )
        return outcome
    }

    private fun buildInput(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
        missingCapabilities: Set<Capability>,
    ): PolicyInput =
        PolicyInput(
            baseRisk = descriptor.baseRisk,
            operationClass = descriptor.operationClass,
            mode = request.mode,
            profile = request.profile,
            source = toolCallSourceOf(descriptor),
            executionTarget = request.executionTarget,
            dataOrigin = request.dataOrigin,
            scope = request.scope,
            overwritesExisting = request.overwritesExisting,
            codeOrCommandChanged = request.codeOrCommandChanged,
            sourceBindingChanged = request.sourceBindingChanged,
            missingCapabilities = missingCapabilities,
            egress = request.egress,
            originSeenInSession = request.originSeenInSession,
            lanScopes = request.lanScopes,
        )

    private fun toolCallSourceOf(descriptor: ToolDescriptor): ToolCallSource =
        when (val origin = descriptor.origin) {
            ToolOrigin.BuiltInOrigin -> ToolCallSource.BuiltIn
            is ToolOrigin.McpOrigin -> ToolCallSource.Mcp(McpServerId(origin.serverId), descriptor.schemaHash.hex)
        }

    /** The approval binding: registry contract facts + trusted request facts; args hashed canonically. */
    private fun buildBinding(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
    ): ApprovalBinding =
        ApprovalBinding(
            toolCallId = request.toolCallId,
            toolName = descriptor.name.value,
            toolVersion = descriptor.version.value.toString(),
            schemaHash = descriptor.schemaHash.hex,
            scopeRef = request.scope?.toScopeRef() ?: UNSCOPED_SCOPE_REF,
            sessionId = request.sessionId,
            executionTarget = request.executionTarget,
            uiToken = request.uiToken,
            argsHash = sha256Hex(CanonicalArgs.canonicalize(request.args)),
        )

    private fun deniedFor(turnId: String): Set<String> =
        synchronized(deniedLock) {
            deniedByTurn[turnId]?.toSet() ?: emptySet()
        }

    private fun markDenied(
        turnId: String,
        fingerprint: String,
    ) {
        synchronized(deniedLock) {
            deniedByTurn.getOrPut(turnId) { mutableSetOf() }.add(fingerprint)
            if (deniedByTurn.size > MAX_TRACKED_TURNS) {
                val lru = deniedByTurn.keys.first()
                if (lru != turnId) deniedByTurn.remove(lru)
            }
        }
    }

    private fun rejectionCode(code: MintRejectionCode): DispatchOutcomeCode =
        when (code) {
            MintRejectionCode.NOT_FOUND -> DispatchOutcomeCode.APPROVAL_NOT_FOUND
            MintRejectionCode.PENDING -> DispatchOutcomeCode.APPROVAL_PENDING
            MintRejectionCode.DENIED -> DispatchOutcomeCode.APPROVAL_DENIED
            MintRejectionCode.EXPIRED -> DispatchOutcomeCode.APPROVAL_EXPIRED
            MintRejectionCode.CONSUMED -> DispatchOutcomeCode.APPROVAL_CONSUMED
        }

    private fun sourceOf(proof: ApprovalProof?): DecisionSource =
        if (proof != null) DecisionSource.USER else DecisionSource.POLICY

    private fun sha256Hex(text: String): String =
        Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    private fun charUtf8Length(c: Char): Int =
        when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.code < 0x10000 -> 3
            else -> 4
        }

    companion object {
        /** The action-identity placeholder for the call-instance id and UI token (see [actionFingerprint]). */
        const val ACTION_IDENTITY = "action"

        /** Calls without a user scope bind this canonical scope ref (never blank — the hash needs a value). */
        const val UNSCOPED_SCOPE_REF = "unscoped"

        /** Bounded memory for same-turn denial sets (least-recently-used eviction). */
        const val MAX_TRACKED_TURNS = 128
    }

    /** Per-dispatch mutable state shared by the stage methods (one instance per dispatch). */
    private class DispatchContext {
        /** 1-based attempt number within this dispatch (doc 11 section 3.3 attemptId). */
        var attemptId: Int = 1
        var policyDecidedAt: Long? = null
        var riskLevel: RiskLevel? = null
        var approvalAcquiredAt: Long? = null
        var executionStartedAt: Long? = null
        var bindingHash: String? = null
        var actionFingerprint: String? = null
        var outputHash: String? = null
        var outputTruncated: Boolean = false
        var stopped: StopResult? = null
        var descriptor: ToolDescriptor? = null
        var executor: ToolExecutor? = null

        /** The proof THIS attempt will spend at execution start (null = approval-free). */
        var attemptProof: ApprovalProof? = null

        data class StopResult(
            val outcome: ToolDispatchOutcome,
            val source: DecisionSource,
        )
    }
}
