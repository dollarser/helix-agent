package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDiscoveryHints
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

// Model-facing bounds (defensive re-reads; the input schema already enforces these).
private const val MAX_URL_LEN: Int = 2048
private const val MAX_BODY_CHARS: Int = 262_144

// The fixed body-read cap the tool asks the transport for (well under the 512 KiB output cap below,
// so a fetched body always fits in the tool's output envelope).
private const val FETCH_BODY_BYTES: Long = 256L * 1024

// Stable outcome status strings; they appear in BOTH the output schema enum and the emitted output,
// so a one-sided drift is impossible. Only the Completed statuses appear here — TIMEOUT maps to
// ToolExecutorResult.TimedOut and ERROR to Failed (bounded reason), matching the HXA-064/065 pattern.
private const val ST_FETCHED: String = "fetched"
private const val ST_REFUSED: String = "refused"

// ===========================================================================
// http_fetch — a bounded GET/HEAD with full SSRF / redirect / peer / scope re-checks
// ===========================================================================
object HttpFetchTool {
    const val NAME: String = "http_fetch"

    const val VERSION: Int = 1

    @Suppress("LongMethod") // model-facing descriptor kept as one readable block
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Fetch one HTTP/HTTPS URL with GET or HEAD for raw response text, status and media type. " +
                    "Use web_fetch for readable page text, or browser_open for JavaScript, login and interaction. " +
                    "Does not use browser cookies or extract page text. The response body is bounded to 256 KiB. " +
                    "Inspect httpStatus and truncated; fetched means a response was received, not HTTP success. " +
                    "A refused result includes a reason; do not bypass its network or scope restriction.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("url", stringSchema(MAX_URL_LEN, "The absolute http/https URL to fetch."))
                            put(
                                "method",
                                enumSchema(
                                    listOf("GET", "HEAD"),
                                    "GET (default) reads the bounded body; " +
                                        "HEAD reads response metadata without a body.",
                                ),
                            )
                        },
                    required = listOf("url"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_FETCHED, ST_REFUSED),
                                    "fetched, or refused (an SSRF / URL-Policy / scope denial with a " +
                                        "stable reason code).",
                                ),
                            )
                            put(
                                "finalUrl",
                                stringSchema(
                                    MAX_URL_LEN,
                                    "The URL actually fetched after redirects; empty when refused.",
                                ),
                            )
                            put("httpStatus", integerSchema("The final HTTP status code; 0 when refused."))
                            put("contentType", stringSchema(256, "The response Content-Type; empty when absent."))
                            put("body", stringSchema(MAX_BODY_CHARS, "The UTF-8 decoded, bounded response body."))
                            put(
                                "byteLength",
                                integerSchema(
                                    "Declared Content-Length when present, otherwise bytes observed; " +
                                        "not always the complete resource size.",
                                ),
                            )
                            put(
                                "truncated",
                                buildJsonObject {
                                    put("type", JsonPrimitive("boolean"))
                                    put("description", JsonPrimitive("True when the body was cut to the byte cap."))
                                },
                            )
                            put("redirectCount", integerSchema("Number of redirect hops followed."))
                            put("reason", stringSchema(128, "Stable refusal reason code; empty on a fetch."))
                        },
                    required =
                        listOf(
                            "status",
                            "finalUrl",
                            "httpStatus",
                            "contentType",
                            "body",
                            "byteLength",
                            "truncated",
                            "redirectCount",
                            "reason",
                        ),
                ),
            operationClass = ToolOperationClass.NETWORK,
            timeout = 30.seconds,
            maxOutputBytes = 512L * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
            discovery = ToolDiscoveryHints(category = "http"),
        )

    fun executor(bridge: HttpFetchBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "LongMethod")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val url =
                    strArg(call.args, "url", MAX_URL_LEN)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'http_fetch' arguments: 'url' must be a non-empty string",
                        )
                val method =
                    (call.args["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.uppercase()
                        ?: "GET"
                if (method != "GET" && method != "HEAD") {
                    return ToolExecutorResult.Failed(
                        "invalid 'http_fetch' arguments: 'method' must be GET or HEAD",
                    )
                }
                val out =
                    bridge.fetch(
                        HttpFetchRequest(
                            url = url,
                            method = method,
                            maxBodyBytes = FETCH_BODY_BYTES,
                            deadlineMillis = call.deadline.toEpochMilli(),
                        ),
                    )
                return when (out.status) {
                    HttpFetchStatus.TIMEOUT -> {
                        ToolExecutorResult.TimedOut
                    }

                    HttpFetchStatus.ERROR -> {
                        ToolExecutorResult.Failed(bounded(out.reason))
                    }

                    HttpFetchStatus.FETCHED,
                    HttpFetchStatus.REFUSED,
                    -> {
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put(
                                    "status",
                                    JsonPrimitive(
                                        if (out.status ==
                                            HttpFetchStatus.FETCHED
                                        ) {
                                            ST_FETCHED
                                        } else {
                                            ST_REFUSED
                                        },
                                    ),
                                )
                                put(
                                    "finalUrl",
                                    JsonPrimitive(
                                        if (out.status ==
                                            HttpFetchStatus.FETCHED
                                        ) {
                                            out.finalUrl
                                        } else {
                                            ""
                                        },
                                    ),
                                )
                                put("httpStatus", JsonPrimitive(out.httpStatus))
                                put(
                                    "contentType",
                                    JsonPrimitive(if (out.status == HttpFetchStatus.FETCHED) out.contentType else ""),
                                )
                                put("body", JsonPrimitive(if (out.status == HttpFetchStatus.FETCHED) out.body else ""))
                                put("byteLength", JsonPrimitive(out.bodyBytes))
                                put("truncated", JsonPrimitive(out.truncated))
                                put("redirectCount", JsonPrimitive(out.redirectCount))
                                put("reason", JsonPrimitive(out.reason))
                            },
                        )
                    }
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        bridge: HttpFetchBridge,
    ) {
        val d = descriptor()

        registry.register(d, executor(bridge))
    }
}

// ===========================================================================
// Registration
// ===========================================================================
object HttpFetchTools {
    /**
     * Registers the `http_fetch` contract and implementation against the shared [bridge]. Called
     * once from the app container (which owns the production [HttpFetchBridgeImpl]); tests build a
     * [ToolRegistry] / [ToolRegistry] pair and a fake bridge.
     */
    fun registerAll(
        registry: ToolRegistry,
        bridge: HttpFetchBridge,
    ) {
        HttpFetchTool.register(registry, bridge)
    }
}
