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
 * One registered tool IMPLEMENTATION, bound to the exact (name, version) contract. The
 * descriptor is the contract (registry); the executor is the code (this registry). The two
 * are registered separately on purpose: a contract without an implementation is legal
 * (e.g. a tool whose backend is not yet available) and the dispatcher fails it closed with
 * a stable error rather than executing anything.
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
)

/** The executor's terminal report for one call; exactly one of the four. */
sealed interface ToolExecutorResult {
    /** Finished within the deadline; [output] is the raw (unvalidated) tool output. */
    data class Completed(
        val output: JsonElement,
    ) : ToolExecutorResult

    /** A terminal tool failure with a stable, model-visible message. */
    data class Failed(
        val detail: String,
    ) : ToolExecutorResult

    /** The deadline was reached (or the implementation chose to stop at it). */
    data object TimedOut : ToolExecutorResult

    /** The cancel signal fired while running; side-effect state is unknown to the framework. */
    data object Cancelled : ToolExecutorResult
}

/**
 * The in-process registry of tool IMPLEMENTATIONS (roadmap HXA-035). Mirrors
 * [ToolRegistry]'s identity rules: an implementation is bound to the exact (name, version)
 * contract; registering a second implementation for the same pair FAILS (a silent
 * replacement would execute different code under an approved binding).
 */
class ToolImplementationRegistry {
    private val lock = Any()
    private val byNameVersion: MutableMap<Pair<ToolName, ToolVersion>, ToolExecutor> = LinkedHashMap()

    /** Registers [executor] for the exact (name, version) of [descriptor]; duplicate fails. */
    fun register(
        descriptor: ToolDescriptor,
        executor: ToolExecutor,
    ): ToolExecutor =
        synchronized(lock) {
            val key = descriptor.name to descriptor.version
            require(byNameVersion[key] == null) {
                "duplicate tool implementation: ${descriptor.name.value} v${descriptor.version.value}"
            }
            byNameVersion[key] = executor
            executor
        }

    /** The implementation for the exact (name, version) contract; fails when absent. */
    fun resolve(
        name: ToolName,
        version: ToolVersion,
    ): ToolExecutor {
        val executor = synchronized(lock) { byNameVersion[name to version] }
        requireNotNull(executor) { "no implementation for ${name.value} v${version.value}" }
        return executor
    }
}
