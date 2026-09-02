package com.helix.runtime.quickjs.tool

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.runtime.quickjs.JsCancellation
import com.helix.runtime.quickjs.JsExecuteParams
import com.helix.runtime.quickjs.JsExecutionLimits
import com.helix.runtime.quickjs.JsExecutionResult
import com.helix.runtime.quickjs.JsExecutionStatus
import com.helix.runtime.quickjs.JsHash
import com.helix.tools.framework.CanonicalArgs
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The synchronous, bounded execution seam the `code.javascript.run` tool calls (HXA-053).
 *
 * Production is backed by [com.helix.runtime.quickjs.JsExecutionClient] (the HXA-051/052
 * isolated-service protocol); tests inject a fake that returns canned [JsExecutionResult]s.
 * The contract is exactly the client's: one call = one execution = one stable
 * [JsExecutionResult] with exactly one of the 11 [JsExecutionStatus] values; the client never
 * retries and never fakes success. Concurrency is the CALLER's (the Tool Scheduler's QuickJS
 * lane) responsibility — this seam never parallelizes on its own.
 */
fun interface JsExecutor {
    fun execute(
        params: JsExecuteParams,
        cancellation: JsCancellation?,
    ): JsExecutionResult
}

/**
 * The `code.javascript.run` built-in tool (roadmap HXA-053): runs Agent-generated JavaScript
 * in the isolated, offline QuickJS backend (doc 03) and backfills the bounded JSON result.
 *
 * Contract (doc 03 §4.1 / §5, mobile-tool-orchestration §3.1):
 * - [ToolOperationClass.CODE_EXECUTION], base risk [RiskLevel.L2] (per-call approval, like the
 *   mutation tools); a generic L2 — no auto-approve path, no Trusted Workspace / batch reuse.
 * - [ExecutionTargetType.LOCAL_QUICKJS]: the platform's QuickJS lane serializes executions to
 *   single concurrency, and CODE_EXECUTION is exclusive — so two JS calls never run in parallel
 *   and never overlap a file tool's effect window (the footprint is derived, not self-declared).
 * - The model sees ONLY the business inputs [code] and [input]; the §4.1 limits are NEVER
 *   schema parameters (the model cannot raise them) — the executor applies the fixed
 *   [JsExecutionLimits.DEFAULTS] and the isolated backend enforces them again.
 *
 * The 11 [JsExecutionStatus] outcomes map to the dispatcher's stable ToolResult semantics:
 * SUCCESS → a bounded JSON result; TIMEOUT → the dispatcher timeout; CANCELLED → the
 * dispatcher cancellation; every other non-success is a stable failure that never claims
 * success. Each attempt also emits a bounded, redacted [JsExecutionResult]-derived audit block
 * (source/output SHA-256, input summary, applied limits, terminal status — doc 03 §4.8).
 */
@Suppress("TooManyFunctions") // one helper per schema / result-mapping concern
object CodeJavascriptRunTool {
    const val NAME: String = "code.javascript.run"

    const val VERSION: Int = 1

    /** The §4.1 source limit, expressed as a coarse model-facing code-point ceiling. */
    private const val MAX_SOURCE_CODE_POINTS: Long = JsExecutionLimits.DEFAULT_MAX_SOURCE_BYTES.toLong()

    /** Model-visible error detail is bounded well below the client's own 2048-char cap. */
    private const val MAX_DETAIL_CHARS: Int = 512

    /**
     * The registered contract. Input: `code` (the `helixMain` body) + optional `input` (a JSON
     * value). NO limit parameter is exposed — limits are fixed §4.1 defaults applied by the
     * backend. Output: the bounded JSON result + its byte length.
     */
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Run Agent-generated JavaScript (a `helixMain` body) in an isolated, offline " +
                    "QuickJS process and return its JSON result. No network, file or Android " +
                    "access; fixed 10 s / 64 MiB / 256 KiB output limits.",
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.CODE_EXECUTION,
            baseRisk = RiskLevel.L2,
            // Framework hard bound, comfortably above the isolated client's own bounded
            // duration (10 s wall + bounded bind/grace) so the client — not the dispatcher —
            // is what settles the QuickJS deadline.
            timeout = 45.seconds,
            // result (≤ 256 KiB JSON document) + the small metadata headroom.
            maxOutputBytes =
                JsExecutionLimits.DEFAULT_MAX_OUTPUT_BYTES.toLong() + 16L * 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_QUICKJS,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun inputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "code",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(MAX_SOURCE_CODE_POINTS))
                            put(
                                "description",
                                JsonPrimitive(
                                    "The `helixMain` function body to run. It may read the " +
                                        "closure variable `input` and must `return` a JSON-" +
                                        "serializable value.",
                                ),
                            )
                        },
                    )
                    put(
                        "input",
                        buildJsonObject {
                            put(
                                "type",
                                JsonArray(
                                    listOf(
                                        JsonPrimitive("object"),
                                        JsonPrimitive("array"),
                                        JsonPrimitive("string"),
                                        JsonPrimitive("number"),
                                        JsonPrimitive("integer"),
                                        JsonPrimitive("boolean"),
                                    ),
                                ),
                            )
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional JSON value passed to the code as the closure " +
                                        "variable `input` (bounded to 2 MiB).",
                                ),
                            )
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("code"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun outputSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "result",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("The JSON document the code returned (stringified)."),
                            )
                        },
                    )
                    put(
                        "outputBytes",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Byte length of `result`."))
                        },
                    )
                },
            )
            put(
                "required",
                JsonArray(listOf(JsonPrimitive("result"), JsonPrimitive("outputBytes"))),
            )
            put("additionalProperties", JsonPrimitive(false))
        }

    /** The implementation bound to [descriptor]; [runner] is the execution seam (production or fake). */
    fun executor(runner: JsExecutor): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return executeOnce(runner, call)
            }
        }

    /**
     * One execution attempt. Absent `input` → null (no input); present `input` → exactly one
     * canonicalized JSON document. The §4.1 limits are the fixed [JsExecutionLimits.DEFAULTS] —
     * never model arguments. A fresh id per attempt: the isolated instance is one-shot and its
     * reclamation is asynchronous, so reusing an id (e.g. the toolCallId) across a retry could
     * rebind a not-yet-reclaimed instance. The id is internal (never bound into the approval
     * hash), so it is free to be random.
     */
    private fun executeOnce(
        runner: JsExecutor,
        call: ExecutableToolCall,
    ): ToolExecutorResult {
        val code =
            (call.args["code"] as? JsonPrimitive)?.content
                ?: return ToolExecutorResult.Failed("invalid 'code.javascript.run' arguments: 'code' must be a string")
        val inputBytes: ByteArray? =
            call.args["input"]?.let { CanonicalArgs.canonicalize(it).toByteArray(StandardCharsets.UTF_8) }
        val limits = JsExecutionLimits.DEFAULTS
        val params =
            JsExecuteParams(
                executionId = UUID.randomUUID().toString(),
                source = code,
                inputJsonUtf8 = inputBytes,
                limits = limits,
            )
        val result = runner.execute(params, JsCancellation { call.cancel.isCancelled() })
        return mapResult(result, code, inputBytes, limits)
    }

    /** Registers both the contract and the implementation in the given registries. */
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        runner: JsExecutor,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(runner))
    }

    /**
     * Maps the closed 11-status [JsExecutionResult] to the dispatcher's stable ToolResult
     * semantics (the catch-all never returns success).
     */
    private fun mapResult(
        result: JsExecutionResult,
        code: String,
        inputBytes: ByteArray?,
        limits: JsExecutionLimits,
    ): ToolExecutorResult {
        val audit = executionDetail(result, code, inputBytes, limits)
        return when (result.status) {
            JsExecutionStatus.SUCCESS -> successResult(result, audit)
            JsExecutionStatus.TIMEOUT -> ToolExecutorResult.TimedOut
            JsExecutionStatus.CANCELLED -> ToolExecutorResult.Cancelled
            else -> failureResult(result, audit)
        }
    }

    /** SUCCESS → the bounded JSON result (the only success path). */
    private fun successResult(
        result: JsExecutionResult,
        audit: JsonObject,
    ): ToolExecutorResult.Completed {
        val text = String(result.outputUtf8, StandardCharsets.UTF_8)
        return ToolExecutorResult.Completed(
            output =
                buildJsonObject {
                    put("result", JsonPrimitive(text))
                    put("outputBytes", JsonPrimitive(result.outputBytes))
                },
            auditDetail = audit,
        )
    }

    /** Any non-success, non-timeout, non-cancelled status → a stable failure (never a fake success). */
    private fun failureResult(
        result: JsExecutionResult,
        audit: JsonObject,
    ): ToolExecutorResult.Failed {
        val (detail, sideEffectFree) = failureSpec(result)
        return ToolExecutorResult.Failed(detail, sideEffectFree = sideEffectFree, auditDetail = audit)
    }

    /** The (model-visible message, confirmed side-effect-free?) for each failure status. */
    private fun failureSpec(result: JsExecutionResult): Pair<String, Boolean> =
        when (result.status) {
            JsExecutionStatus.INTERRUPTED -> {
                "JavaScript execution was interrupted before it could finish." to true
            }

            JsExecutionStatus.OOM -> {
                "JavaScript execution ran out of memory (heap limit 64 MiB)." to true
            }

            JsExecutionStatus.JS_ERROR -> {
                "JavaScript error: ${boundedDetail(result.detail)}" to true
            }

            JsExecutionStatus.OUTPUT_LIMIT -> {
                "JavaScript output exceeded the 256 KiB output limit." to true
            }

            JsExecutionStatus.CRASHED -> {
                "The isolated JavaScript process crashed before the result could be recovered." to false
            }

            JsExecutionStatus.REQUEST_REJECTED -> {
                "JavaScript request was rejected before execution: ${boundedDetail(result.detail)}" to true
            }

            JsExecutionStatus.BIND_FAILED -> {
                "Could not start the isolated JavaScript executor." to true
            }

            JsExecutionStatus.UNKNOWN -> {
                "The JavaScript execution result is unknown; nothing was verified." to false
            }

            // SUCCESS / TIMEOUT / CANCELLED are handled by [mapResult] before this point; reaching
            // them here would be a routing bug — fail closed, never map a failure to a success.
            else -> {
                error("failureSpec called for a non-failure status: ${result.status}")
            }
        }

    /**
     * The bounded, REDACTED §4.8 execution audit block (doc 03 §4.8): source SHA-256, input
     * summary (size + hash, never the body), the applied limits, the terminal JS status and the
     * output SHA-256. No code or input body ever enters it.
     */
    private fun executionDetail(
        result: JsExecutionResult,
        code: String,
        inputBytes: ByteArray?,
        limits: JsExecutionLimits,
    ): JsonObject =
        buildJsonObject {
            put("status", JsonPrimitive(result.status.name))
            put("sourceSha256", JsonPrimitive(JsHash.sha256Utf8(code)))
            put("sourceBytes", JsonPrimitive(code.toByteArray(StandardCharsets.UTF_8).size.toLong()))
            put("inputBytes", JsonPrimitive((inputBytes?.size ?: 0).toLong()))
            put("inputSha256", JsonPrimitive(inputBytes?.let(JsHash::sha256Hex) ?: ""))
            put("outputBytes", JsonPrimitive(result.outputBytes))
            put("outputSha256", JsonPrimitive(result.outputSha256Hex))
            put(
                "limits",
                buildJsonObject {
                    put("timeoutMs", JsonPrimitive(limits.timeoutMs))
                    put("memoryBytes", JsonPrimitive(limits.memoryBytes))
                    put("maxSourceBytes", JsonPrimitive(limits.maxSourceBytes.toLong()))
                    put("maxInputBytes", JsonPrimitive(limits.maxInputBytes.toLong()))
                    put("maxOutputBytes", JsonPrimitive(limits.maxOutputBytes.toLong()))
                },
            )
            // The service identity is present (>= 0) only when an isolated instance actually ran.
            put("isolated", JsonPrimitive(result.serviceUid >= 0))
        }

    /** Bounds the engine detail for the model-visible message (never the audit, which keeps it raw). */
    private fun boundedDetail(detail: String): String {
        val trimmed = detail.trim()
        return if (trimmed.length <= MAX_DETAIL_CHARS) trimmed else trimmed.take(MAX_DETAIL_CHARS) + "…"
    }
}
