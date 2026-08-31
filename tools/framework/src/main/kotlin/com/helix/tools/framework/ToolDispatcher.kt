package com.helix.tools.framework

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.Hex
import com.helix.core.model.McpServerId
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
)

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

    /** Execution started and ended non-successfully with a stable error code. */
    data class ExecutionFailed(
        val code: DispatchOutcomeCode,
        val detail: String,
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
 * 8. **audit** — exactly one [DispatchAuditEvent] per dispatch (no bodies, only hashes,
 *    stage timestamps and the decision source).
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

    /** The single entry point; see the class KDoc for the pipeline. */
    fun dispatch(request: ToolDispatchRequest): ToolDispatchOutcome {
        val startedAt = clock.now()
        val ctx = DispatchContext()
        val descriptor = validateStage(request, ctx)
        val proof = if (descriptor != null) policyStage(request, descriptor, ctx) else null
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
     */
    private fun policyStage(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
        ctx: DispatchContext,
    ): ApprovalProof? {
        val evaluation = capabilityCenter.evaluate(descriptor.requiredCapabilities)
        val policy =
            policyEngine.evaluate(
                buildInput(request, descriptor, evaluation.missing.toSet()),
                ruleProvider(),
            )
        ctx.policyDecidedAt = clock.now().toEpochMilli()
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
                acquireApproval(request, descriptor, decision, ctx)
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
        val acquisition = approvals.acquire(ApprovalRequest(binding, decision.detail))
        ctx.approvalAcquiredAt = clock.now().toEpochMilli()
        return when (acquisition) {
            is ApprovalAcquisition.Approved -> {
                acquisition.proof
            }

            is ApprovalAcquisition.Rejected -> {
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

            ToolExecutorResult.TimedOut -> {
                finish(
                    request,
                    startedAt,
                    ctx,
                    ToolDispatchOutcome.ExecutionFailed(
                        DispatchOutcomeCode.TIMEOUT,
                        "tool exceeded its deadline; the stable timeout error is the model-visible outcome",
                    ),
                    DecisionSource.FRAMEWORK,
                )
            }

            ToolExecutorResult.Cancelled -> {
                finish(
                    request,
                    startedAt,
                    ctx,
                    ToolDispatchOutcome.ExecutionFailed(
                        DispatchOutcomeCode.CANCELLED_AFTER_START,
                        "cancellation fired after execution started; side-effect state is unknown",
                    ),
                    DecisionSource.FRAMEWORK,
                )
            }

            is ToolExecutorResult.Failed -> {
                finish(
                    request,
                    startedAt,
                    ctx,
                    ToolDispatchOutcome.ExecutionFailed(DispatchOutcomeCode.TOOL_FAILED, result.detail),
                    DecisionSource.FRAMEWORK,
                )
            }
        }
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
                startedAt = startedAt.toEpochMilli(),
                policyDecidedAt = ctx.policyDecidedAt,
                approvalAcquiredAt = ctx.approvalAcquiredAt,
                executionStartedAt = ctx.executionStartedAt,
                finishedAt = clock.now().toEpochMilli(),
                code = code,
                decisionSource = source,
                bindingHash = ctx.bindingHash,
                actionFingerprint = ctx.actionFingerprint,
                outputHash = ctx.outputHash,
                outputTruncated = ctx.outputTruncated,
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
        var policyDecidedAt: Long? = null
        var approvalAcquiredAt: Long? = null
        var executionStartedAt: Long? = null
        var bindingHash: String? = null
        var actionFingerprint: String? = null
        var outputHash: String? = null
        var outputTruncated: Boolean = false
        var stopped: StopResult? = null
        var descriptor: ToolDescriptor? = null
        var executor: ToolExecutor? = null

        data class StopResult(
            val outcome: ToolDispatchOutcome,
            val source: DecisionSource,
        )
    }
}
