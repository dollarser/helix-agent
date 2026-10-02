package com.helix.tools.framework

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Cooperative cancellation for one dispatch (roadmap HXA-035 pipeline step "timeout/cancel").
 * The dispatcher checks the signal at stage boundaries and before starting execution; the
 * executor is expected to poll it while running and report [ToolExecutorResult.Cancelled].
 * Cancellation is cooperative by design: the framework never kills a mid-effect call and
 * never blindly replays one (doc 11).
 */
interface CancelSignal {
    fun isCancelled(): Boolean
}

/** The default signal: never cancelled. */
data object NoCancellation : CancelSignal {
    override fun isCancelled(): Boolean = false
}

/**
 * One registered tool implementation, atomically bound with its exact descriptor in ToolRegistry.
 * Availability is distinct from installation; a missing or revoked binding fails before execution.
 * Trusted Job observers enter through dispatchCompletion; this synchronous method never waits for them.
 */
interface ToolExecutor {
    /**
     * Runs one validated call. [call.deadline] is an absolute bound the implementation must
     * honor: when it cannot finish before the deadline it returns
     * [ToolExecutorResult.TimedOut] (the dispatcher turns that into the stable timeout
     * error, security doc section 7.3).
     */
    fun execute(call: ExecutableToolCall): ToolExecutorResult
}

/**
 * What the dispatcher hands to an executor: fully validated and bound. Arguments already
 * passed the input schema (all violations), the execution target is the platform-decided
 * binding, and the deadline encodes the descriptor's hard timeout (doc 02 section 7.1
 * Timeout/Cancellation 包装).
 */
data class ExecutableToolCall(
    val toolCallId: String,
    val toolName: String,
    val toolVersion: String,
    val args: JsonObject,
    val executionTarget: ExecutionTargetType,
    val deadline: Instant,
    val cancel: CancelSignal,
    /** Trusted local ownership context; never supplied by model arguments. */
    val sessionId: String? = null,
    val turnId: String? = null,
    /** Exact user scope resolved by the Dispatcher, never a model argument. */
    val authorizationScopeRef: String? = null,
)

/**
 * The executor's terminal report for one call; exactly one of the four. [Completed] and [Failed]
 * carry the optional bounded, REDACTED [Completed.auditDetail] (HXA-053) — executor-reported
 * hashes, sizes and fixed limits, NEVER an argument or output body (doc 11: audit records no
 * bodies). The dispatcher routes it onto the single per-dispatch audit event so an execution with
 * its own audit fields (QuickJS source/output SHA-256, input summary, applied limits, terminal JS
 * status — doc 03 section 4.8) reaches the audit chain through the SAME single-emitter pipeline as
 * every other tool. Null for tools that report none.
 */
sealed interface ToolExecutorResult {
    /**
     * Finished within the deadline; [output] is the raw (unvalidated) tool output.
     * [auditDetail] is the optional redacted executor metadata — never model-visible (the model
     * sees [output] only), routed to the audit event by the dispatcher.
     */
    data class Completed(
        val output: JsonElement,
        val auditDetail: JsonObject? = null,
        val visualArtifact: com.helix.core.model.VisualArtifact? = null,
    ) : ToolExecutorResult

    /**
     * A terminal tool failure with a stable, model-visible message.
     *
     * [sideEffectFree] is the platform executor's CONFIRMED report that this attempt
     * produced no side effect (doc 11 section 3.3: 只允许确认零副作用、相同 envelope、
     * 同/更强隔离的有界技术重试). Examples of true positives: the companion Runtime's
     * Binder died before the Job was accepted, a connection failed before the request was
     * sent. When in doubt this MUST stay false — an unconfirmed failure is terminal and
     * the next run of the same action is a NEW ToolCall with a new approval.
     *
     * [auditDetail] is the optional redacted executor metadata routed to the audit event (the
     * model sees [detail] only).
     */
    data class Failed(
        val detail: String,
        val sideEffectFree: Boolean = false,
        /** True when execution may have produced effects that cannot yet be verified. */
        val requiresReview: Boolean = false,
        val auditDetail: JsonObject? = null,
    ) : ToolExecutorResult {
        init {
            require(!sideEffectFree || !requiresReview) {
                "a confirmed side-effect-free failure cannot require side-effect review"
            }
        }
    }

    /** The deadline was reached (or the implementation chose to stop at it). */
    data object TimedOut : ToolExecutorResult

    /** Executor-confirmed effect truth for a deadline terminal. Generic watchdog timeouts use [TimedOut]. */
    data class TimedOutWithEffectTruth(
        val detail: String,
        val sideEffectFree: Boolean,
        val requiresReview: Boolean,
        val auditDetail: JsonObject? = null,
    ) : ToolExecutorResult {
        init {
            require(!sideEffectFree || !requiresReview) {
                "a confirmed side-effect-free timeout cannot require side-effect review"
            }
        }
    }

    /** The cancel signal fired while running; side-effect state is unknown to the framework. */
    data object Cancelled : ToolExecutorResult

    /** Executor-confirmed effect truth for a cancellation that happened after execution started. */
    data class CancelledWithEffectTruth(
        val detail: String,
        val sideEffectFree: Boolean,
        val requiresReview: Boolean,
        val auditDetail: JsonObject? = null,
    ) : ToolExecutorResult {
        init {
            require(!sideEffectFree || !requiresReview) {
                "a confirmed side-effect-free cancellation cannot require side-effect review"
            }
        }
    }
}
