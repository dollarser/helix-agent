@file:Suppress("TooManyFunctions") // the 11 browser.* tools share the private schema/arg mapping helpers

package com.helix.tools.browser

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
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
import kotlinx.serialization.json.intOrNull
import kotlin.time.Duration.Companion.seconds

// The `browser.*` built-in tools (roadmap HXA-062, doc 09 §3.3): the 11 in-app WebView actions —
// open / navigate / back / forward / reload / snapshot / find / click / type / scroll / screenshot.
//
// Every tool is a thin, fail-closed mapper over the [BrowserToolBridge] port (implemented by
// :feature:browser's `BrowserToolBridgeImpl`, wired in the app). The tool does no WebView work
// itself: it parses its (already schema-validated) arguments, calls exactly one port method, and
// maps the closed outcome to a stable [ToolExecutorResult] — never claiming success on a refusal,
// a stale token, an unknown tab or a timeout. The port (the WebView / token / policy boundary) is
// where the security work lives; this file only shapes the model-facing contract.
//
// Contract highlights (doc 09 §3.3):
// - [BrowserClickTool] / [BrowserTypeTool] may ONLY consume the short-lived node token minted by
//   the most recent [BrowserSnapshotTool]; the port re-validates it against the tab's LIVE state
//   (origin / navigation generation / fingerprint / TTL) and a navigation, refresh, DOM change or
//   TTL expiry makes it stale (a `stale-token` outcome the model reads as "take a fresh snapshot").
// - `browser.type` password / payment / one-time-code fields are refused by default (doc 09 §3.3)
//   — a `refused` outcome carrying the category, never a typed character.
// - [BrowserScreenshotTool] captures only the Helix WebView and saves to the Workspace (doc 09
//   §3.3); the model sees a model-safe Workspace reference plus size/SHA-256, never a raw path.
//
// Output convention: every Completed output is schema-conformant and carries NO JSON nulls — a
// logically-absent value (reason on success, href/name when not applicable) is emitted as `""`
// and an unresolved node index as `-1`.

// ---------------------------------------------------------------------------
// Shared model-facing bounds (mirror the port's own extraction/action bounds, so a port that
// drifts out of them is caught by schema validation rather than silently truncated by the wire).
// ---------------------------------------------------------------------------
private const val MAX_TAB_ID: Int = 128
private const val MAX_URL: Int = 2048
private const val MAX_ORIGIN: Int = 256
private const val MAX_TOKEN: Int = 512
private const val MAX_QUERY: Int = 200
private const val MAX_TYPE_TEXT: Int = 1000
private const val MAX_ROLE: Int = 32
private const val MAX_NODE_TEXT: Int = 200
private const val MAX_NODES: Int = 400
private const val MAX_SCROLL_PX: Int = 100_000

/** Model-visible error detail is bounded well below any port message length. */
private const val MAX_DETAIL_CHARS: Int = 512

// Stable outcome status strings; they appear in BOTH the output schema enum and the emitted
// output, so a one-sided drift is impossible.
private const val ST_NAV_STARTED: String = "started"
private const val ST_NAV_DENIED: String = "denied"
private const val ST_HIST_MOVED: String = "moved"
private const val ST_HIST_NO_CHANGE: String = "no-change"
private const val ST_RELOAD_RELOADED: String = "reloaded"
private const val ST_RELOAD_NO_CHANGE: String = "no-change"
private const val ST_ACT_PERFORMED: String = "performed"
private const val ST_ACT_REFUSED: String = "refused"
private const val ST_ACT_STALE: String = "stale-token"
private const val ST_SCROLLED: String = "scrolled"
private const val ST_SAVED: String = "saved"

// ---------------------------------------------------------------------------
// Small schema + argument helpers (file-private).
// ---------------------------------------------------------------------------

private fun stringSchema(
    maxLength: Int,
    description: String,
    minLength: Int? = null,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        if (minLength != null) put("minLength", JsonPrimitive(minLength))
        put("maxLength", JsonPrimitive(maxLength))
        put("description", JsonPrimitive(description))
    }

/** A string schema restricted to a fixed set of values (the closed outcome statuses). */
private fun enumSchema(
    values: List<String>,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", JsonPrimitive(description))
    }

private fun booleanSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }

private fun integerSchema(
    description: String,
    minimum: Int?,
    maximum: Int?,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("integer"))
        if (minimum != null) put("minimum", JsonPrimitive(minimum))
        if (maximum != null) put("maximum", JsonPrimitive(maximum))
        put("description", JsonPrimitive(description))
    }

private fun objectSchema(
    properties: JsonObject,
    required: List<String>,
): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", properties)
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", JsonPrimitive(false))
    }

/** The bounded per-node object shared by the snapshot `nodes` and find `matches` sub-schemas. */
private fun nodeSchema(): JsonObject =
    objectSchema(
        properties =
            buildJsonObject {
                put("index", integerSchema("Position of the node in the snapshot (stable within it).", null, null))
                put(
                    "role",
                    stringSchema(MAX_ROLE, "Semantic role: link, button, field, image, heading or interactive."),
                )
                put("text", stringSchema(MAX_NODE_TEXT, "Bounded visible text (UNTRUSTED page data)."))
                put("value", stringSchema(MAX_NODE_TEXT, "Field value; empty for a password field (never read)."))
                put("href", stringSchema(MAX_NODE_TEXT, "Bounded href; empty when not applicable."))
                put("name", stringSchema(MAX_NODE_TEXT, "Bounded accessible name; empty when not applicable."))
                put("token", stringSchema(MAX_TOKEN, "Short-lived node token; the only handle for click / type."))
            },
        required = listOf("index", "role", "text", "value", "href", "name", "token"),
    )

private fun nodeObject(n: BrowserNodeView): JsonObject =
    buildJsonObject {
        put("index", JsonPrimitive(n.index))
        put("role", JsonPrimitive(n.role))
        put("text", JsonPrimitive(n.text))
        put("value", JsonPrimitive(n.value))
        put("href", JsonPrimitive(n.href))
        put("name", JsonPrimitive(n.name))
        put("token", JsonPrimitive(n.token))
    }

/**
 * Reads a string argument that is present, a string, and within [maxLength]. Arguments are
 * already input-schema-validated before the executor runs; this is a defensive re-read (a wrong
 * shape is a stable `invalid arguments` failure, never a crash).
 */
private fun strArg(
    args: JsonObject,
    key: String,
    maxLength: Int,
): String? =
    (args[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.length <= maxLength }

/** Reads an integer argument within [min]..[max] (defensive re-read; see [strArg]). */
private fun intArg(
    args: JsonObject,
    key: String,
    min: Int,
    max: Int,
): Int? =
    (args[key] as? JsonPrimitive)
        ?.let { p ->
            if (p.isString) p.content.toIntOrNull() else p.intOrNull
        }?.takeIf { it in min..max }

/** Bounds a port error message for the model-visible `detail` (never the audit, which keeps it raw). */
private fun bounded(detail: String): String {
    val t = detail.trim()
    return if (t.length <= MAX_DETAIL_CHARS) t else t.take(MAX_DETAIL_CHARS) + "…"
}

/** The shared `status` field for browser.click / browser.type (performed / refused / stale-token). */
private fun actionStatusSchema(): JsonObject =
    enumSchema(
        listOf(ST_ACT_PERFORMED, ST_ACT_REFUSED, ST_ACT_STALE),
        "performed, refused (sensitive field), or stale-token (take a fresh snapshot).",
    )

// ===========================================================================
// browser.open
// ===========================================================================
object BrowserOpenTool {
    const val NAME: String = "browser.open"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Open a new Helix in-app browser tab, select it, and navigate it to an http/https " +
                    "URL. Returns the new tab id; pass it to the other browser.* tools. An empty " +
                    "url opens a blank tab.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "url",
                                stringSchema(MAX_URL, "The destination URL (http/https), or empty for a blank tab."),
                            )
                        },
                    required = listOf("url"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The id of the new tab."))
                            put("url", stringSchema(MAX_URL, "The committed target URL."))
                            put(
                                "origin",
                                stringSchema(MAX_ORIGIN, "Target origin (about:blank / data:opaque for non-http)."),
                            )
                        },
                    required = listOf("tabId", "url", "origin"),
                ),
            operationClass = ToolOperationClass.NETWORK,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val url =
                    strArg(call.args, "url", MAX_URL)
                        ?: return ToolExecutorResult.Failed("invalid 'browser.open' arguments: 'url' must be a string")
                val out = bridge.open(url)
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("tabId", JsonPrimitive(out.tabId))
                        put("url", JsonPrimitive(out.url))
                        put("origin", JsonPrimitive(out.origin))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.navigate
// ===========================================================================
object BrowserNavigateTool {
    const val NAME: String = "browser.navigate"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Navigate an existing browser tab to an http/https URL through the URL policy. " +
                    "Reports whether the navigation started, was denied by policy (with the reason), " +
                    "or the tab is unknown.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to navigate (from a prior browser.open)."))
                            put("url", stringSchema(MAX_URL, "The destination URL (http/https)."))
                        },
                    required = listOf("tabId", "url"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(listOf(ST_NAV_STARTED, ST_NAV_DENIED), "started, or denied by policy."),
                            )
                            put("url", stringSchema(MAX_URL, "The committed target URL; empty on denial."))
                            put("origin", stringSchema(MAX_ORIGIN, "The target origin; empty on denial."))
                            put(
                                "reason",
                                stringSchema(MAX_DETAIL_CHARS, "The policy denial reason; empty when started."),
                            )
                        },
                    required = listOf("status", "url", "origin", "reason"),
                ),
            operationClass = ToolOperationClass.NETWORK,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.navigate' arguments: 'tabId' must be a string",
                        )
                val url =
                    strArg(call.args, "url", MAX_URL)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.navigate' arguments: 'url' must be a string",
                        )
                val out = bridge.navigate(tabId, url)
                return when (out.status) {
                    NavStatus.STARTED -> {
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("status", JsonPrimitive(ST_NAV_STARTED))
                                put("url", JsonPrimitive(out.url))
                                put("origin", JsonPrimitive(out.origin))
                                put("reason", JsonPrimitive(""))
                            },
                        )
                    }

                    NavStatus.DENIED -> {
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("status", JsonPrimitive(ST_NAV_DENIED))
                                put("url", JsonPrimitive(""))
                                put("origin", JsonPrimitive(""))
                                put("reason", JsonPrimitive(bounded(out.reason)))
                            },
                        )
                    }

                    NavStatus.NO_TAB -> {
                        ToolExecutorResult.Failed("unknown tab: no such browser tab", sideEffectFree = true)
                    }

                    NavStatus.TIMED_OUT -> {
                        ToolExecutorResult.TimedOut
                    }
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.back
// ===========================================================================
object BrowserBackTool {
    const val NAME: String = "browser.back"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Go back one step in a browser tab's history. Returns the resulting URL and the " +
                    "tab's canGoBack / canGoForward flags.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to go back in."))
                        },
                    required = listOf("tabId"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("status", enumSchema(listOf(ST_HIST_MOVED, ST_HIST_NO_CHANGE), "moved, or no-change."))
                            put("url", stringSchema(MAX_URL, "The resulting page URL."))
                            put("origin", stringSchema(MAX_ORIGIN, "The resulting origin."))
                            put("canGoBack", booleanSchema("Whether the tab can go back further."))
                            put("canGoForward", booleanSchema("Whether the tab can go forward."))
                            put("reason", stringSchema(MAX_DETAIL_CHARS, "An explanatory note; empty on success."))
                        },
                    required = listOf("status", "url", "origin", "canGoBack", "canGoForward", "reason"),
                ),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L0,
            timeout = 15.seconds,
            maxOutputBytes = 2048,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.back' arguments: 'tabId' must be a string",
                        )
                val out = bridge.back(tabId)
                return historyResult(out)
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.forward
// ===========================================================================
object BrowserForwardTool {
    const val NAME: String = "browser.forward"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Go forward one step in a browser tab's history. Returns the resulting URL and the " +
                    "tab's canGoBack / canGoForward flags.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to go forward in."))
                        },
                    required = listOf("tabId"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("status", enumSchema(listOf(ST_HIST_MOVED, ST_HIST_NO_CHANGE), "moved, or no-change."))
                            put("url", stringSchema(MAX_URL, "The resulting page URL."))
                            put("origin", stringSchema(MAX_ORIGIN, "The resulting origin."))
                            put("canGoBack", booleanSchema("Whether the tab can go back further."))
                            put("canGoForward", booleanSchema("Whether the tab can go forward."))
                            put("reason", stringSchema(MAX_DETAIL_CHARS, "An explanatory note; empty on success."))
                        },
                    required = listOf("status", "url", "origin", "canGoBack", "canGoForward", "reason"),
                ),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L0,
            timeout = 15.seconds,
            maxOutputBytes = 2048,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.forward' arguments: 'tabId' must be a string",
                        )
                val out = bridge.forward(tabId)
                return historyResult(out)
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

/** Maps a closed [HistoryOutcome] to the back/forward result (shared by [BrowserBackTool] / [BrowserForwardTool]). */
private fun historyResult(out: HistoryOutcome): ToolExecutorResult =
    when (out.status) {
        HistStatus.MOVED,
        HistStatus.NO_CHANGE,
        -> {
            ToolExecutorResult.Completed(
                buildJsonObject {
                    put(
                        "status",
                        JsonPrimitive(
                            if (out.status ==
                                HistStatus.MOVED
                            ) {
                                ST_HIST_MOVED
                            } else {
                                ST_HIST_NO_CHANGE
                            },
                        ),
                    )
                    put("url", JsonPrimitive(out.url))
                    put("origin", JsonPrimitive(out.origin))
                    put("canGoBack", JsonPrimitive(out.canGoBack))
                    put("canGoForward", JsonPrimitive(out.canGoForward))
                    put("reason", JsonPrimitive(bounded(out.reason)))
                },
            )
        }

        HistStatus.NO_TAB -> {
            ToolExecutorResult.Failed("unknown tab: no such browser tab", sideEffectFree = true)
        }

        HistStatus.TIMED_OUT -> {
            ToolExecutorResult.TimedOut
        }
    }

// ===========================================================================
// browser.reload
// ===========================================================================
object BrowserReloadTool {
    const val NAME: String = "browser.reload"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description = "Reload a browser tab's current (committed) page. Returns the resulting URL.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to reload."))
                        },
                    required = listOf("tabId"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(listOf(ST_RELOAD_RELOADED, ST_RELOAD_NO_CHANGE), "reloaded, or no-change."),
                            )
                            put("url", stringSchema(MAX_URL, "The resulting page URL."))
                            put("origin", stringSchema(MAX_ORIGIN, "The resulting origin."))
                            put("reason", stringSchema(MAX_DETAIL_CHARS, "An explanatory note; empty on success."))
                        },
                    required = listOf("status", "url", "origin", "reason"),
                ),
            operationClass = ToolOperationClass.NETWORK,
            baseRisk = RiskLevel.L0,
            timeout = 30.seconds,
            maxOutputBytes = 2048,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.reload' arguments: 'tabId' must be a string",
                        )
                val out = bridge.reload(tabId)
                val status = if (out.status == ReloadStatus.RELOADED) ST_RELOAD_RELOADED else ST_RELOAD_NO_CHANGE
                return when (out.status) {
                    ReloadStatus.RELOADED,
                    ReloadStatus.NO_CHANGE,
                    -> {
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("status", JsonPrimitive(status))
                                put("url", JsonPrimitive(out.url))
                                put("origin", JsonPrimitive(out.origin))
                                put("reason", JsonPrimitive(bounded(out.reason)))
                            },
                        )
                    }

                    ReloadStatus.NO_TAB -> {
                        ToolExecutorResult.Failed("unknown tab: no such browser tab", sideEffectFree = true)
                    }

                    ReloadStatus.TIMED_OUT -> {
                        ToolExecutorResult.TimedOut
                    }
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.snapshot
// ===========================================================================
object BrowserSnapshotTool {
    const val NAME: String = "browser.snapshot"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Capture a bounded semantic snapshot of a browser tab: its url, title, origin, " +
                    "navigation generation, tree fingerprint, and the semantic nodes (links, buttons, " +
                    "fields, images, headings). Each node carries a short-lived token that " +
                    "browser.click / browser.type consume.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to snapshot."))
                        },
                    required = listOf("tabId"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("url", stringSchema(MAX_URL, "The page URL (UNTRUSTED)."))
                            put("title", stringSchema(MAX_NODE_TEXT, "The page title (UNTRUSTED)."))
                            put("origin", stringSchema(MAX_ORIGIN, "The page origin."))
                            put(
                                "navigationGeneration",
                                integerSchema("Navigation generation this snapshot is bound to.", null, null),
                            )
                            put(
                                "fingerprint",
                                stringSchema(MAX_TOKEN, "The host-computed tree fingerprint the tokens are bound to."),
                            )
                            put("truncated", booleanSchema("True when the semantic tree exceeded the node budget."))
                            put("nodeCount", integerSchema("Number of nodes returned.", 0, MAX_NODES))
                            put("nodes", nodesArraySchema())
                        },
                    required =
                        listOf(
                            "url",
                            "title",
                            "origin",
                            "navigationGeneration",
                            "fingerprint",
                            "truncated",
                            "nodeCount",
                            "nodes",
                        ),
                ),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 1024L * 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun nodesArraySchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("items", nodeSchema())
            put("minItems", JsonPrimitive(0))
            put("maxItems", JsonPrimitive(MAX_NODES))
            put("description", JsonPrimitive("The bounded semantic nodes, in document order."))
        }

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.snapshot' arguments: 'tabId' must be a string",
                        )
                val out = bridge.snapshot(tabId)
                if (!out.ok) {
                    return ToolExecutorResult.Failed(bounded(out.message), sideEffectFree = true)
                }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("url", JsonPrimitive(out.url))
                        put("title", JsonPrimitive(out.title))
                        put("origin", JsonPrimitive(out.origin))
                        put("navigationGeneration", JsonPrimitive(out.navigationGeneration))
                        put("fingerprint", JsonPrimitive(out.fingerprint))
                        put("truncated", JsonPrimitive(out.truncated))
                        put("nodeCount", JsonPrimitive(out.nodeCount))
                        put("nodes", JsonArray(out.nodes.map { nodeObject(it) }))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.find
// ===========================================================================
object BrowserFindTool {
    const val NAME: String = "browser.find"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Search the most recent browser.snapshot of a tab for a case-insensitive substring " +
                    "and return the matching nodes and their tokens.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab whose last snapshot is searched."))
                            put(
                                "query",
                                stringSchema(MAX_QUERY, "The case-insensitive substring to find.", minLength = 1),
                            )
                        },
                    required = listOf("tabId", "query"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("query", stringSchema(MAX_QUERY, "The query that was searched."))
                            put("matchCount", integerSchema("Number of matches returned.", 0, MAX_NODES))
                            put("matches", matchesArraySchema())
                        },
                    required = listOf("query", "matchCount", "matches"),
                ),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L0,
            timeout = 10.seconds,
            maxOutputBytes = 32L * 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun matchesArraySchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("array"))
            put(
                "items",
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("index", integerSchema("Position of the node in the snapshot.", null, null))
                            put("role", stringSchema(MAX_ROLE, "Semantic role of the match."))
                            put("text", stringSchema(MAX_NODE_TEXT, "Bounded visible text (UNTRUSTED page data)."))
                            put("token", stringSchema(MAX_TOKEN, "Short-lived node token for this match."))
                        },
                    required = listOf("index", "role", "text", "token"),
                ),
            )
            put("minItems", JsonPrimitive(0))
            put("maxItems", JsonPrimitive(MAX_NODES))
            put("description", JsonPrimitive("The matching nodes, in snapshot order."))
        }

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.find' arguments: 'tabId' must be a string",
                        )
                val query =
                    strArg(call.args, "query", MAX_QUERY)
                        ?.takeIf { it.isNotEmpty() }
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.find' arguments: 'query' must be a non-empty string",
                        )
                val out = bridge.find(tabId, query)
                if (!out.ok) {
                    return ToolExecutorResult.Failed(bounded(out.message), sideEffectFree = true)
                }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("query", JsonPrimitive(out.query))
                        put("matchCount", JsonPrimitive(out.matchCount))
                        put(
                            "matches",
                            JsonArray(
                                out.matches.map {
                                    buildJsonObject {
                                        put("index", JsonPrimitive(it.index))
                                        put("role", JsonPrimitive(it.role))
                                        put("text", JsonPrimitive(it.text))
                                        put("token", JsonPrimitive(it.token))
                                    }
                                },
                            ),
                        )
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.click
// ===========================================================================
object BrowserClickTool {
    const val NAME: String = "browser.click"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Click the node named by a short-lived token from the most recent browser.snapshot of " +
                    "a tab. Password, payment and one-time-code fields are refused; the token must " +
                    "still be valid (a navigation, refresh, DOM change or expiry makes it stale).",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab the token belongs to."))
                            put(
                                "token",
                                stringSchema(MAX_TOKEN, "The node token from the most recent browser.snapshot."),
                            )
                        },
                    required = listOf("tabId", "token"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("status", actionStatusSchema())
                            put(
                                "nodeIndex",
                                integerSchema("The snapshot node index, or -1 when no node was resolved.", null, null),
                            )
                            put("tag", stringSchema(MAX_ROLE, "The element tag; empty when not resolved."))
                            put("role", stringSchema(MAX_ROLE, "The semantic role; empty when not resolved."))
                            put(
                                "reason",
                                stringSchema(MAX_DETAIL_CHARS, "Refusal/staleness reason; empty when performed."),
                            )
                        },
                    required = listOf("status", "nodeIndex", "tag", "role", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.click' arguments: 'tabId' must be a string",
                        )
                val token =
                    strArg(call.args, "token", MAX_TOKEN)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.click' arguments: 'token' must be a string",
                        )
                val out = bridge.click(tabId, token)
                return actionResult(out)
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.type
// ===========================================================================
object BrowserTypeTool {
    const val NAME: String = "browser.type"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Type text into the field named by a short-lived token from the most recent " +
                    "browser.snapshot of a tab. Password, payment and one-time-code fields are " +
                    "refused by default; the token must still be valid.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab the token belongs to."))
                            put(
                                "token",
                                stringSchema(MAX_TOKEN, "The node token from the most recent browser.snapshot."),
                            )
                            put("text", stringSchema(MAX_TYPE_TEXT, "The text to type into the field."))
                        },
                    required = listOf("tabId", "token", "text"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("status", actionStatusSchema())
                            put(
                                "nodeIndex",
                                integerSchema("The snapshot node index, or -1 when no node was resolved.", null, null),
                            )
                            put("tag", stringSchema(MAX_ROLE, "The element tag; empty when not resolved."))
                            put("role", stringSchema(MAX_ROLE, "The semantic role; empty when not resolved."))
                            put(
                                "reason",
                                stringSchema(MAX_DETAIL_CHARS, "Refusal/staleness reason; empty when performed."),
                            )
                        },
                    required = listOf("status", "nodeIndex", "tag", "role", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.type' arguments: 'tabId' must be a string",
                        )
                val token =
                    strArg(call.args, "token", MAX_TOKEN)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.type' arguments: 'token' must be a string",
                        )
                val text =
                    strArg(call.args, "text", MAX_TYPE_TEXT)
                        ?: return ToolExecutorResult.Failed("invalid 'browser.type' arguments: 'text' must be a string")
                val out = bridge.type(tabId, token, text)
                return actionResult(out)
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

/** Maps a closed [ActionOutcome] to the click/type result (shared by [BrowserClickTool] and [BrowserTypeTool]). */
private fun actionResult(out: ActionOutcome): ToolExecutorResult =
    when (out.status) {
        ActionStatus.PERFORMED -> ToolExecutorResult.Completed(actionOutput(ST_ACT_PERFORMED, out))
        ActionStatus.REFUSED -> ToolExecutorResult.Completed(actionOutput(ST_ACT_REFUSED, out))
        ActionStatus.STALE_TOKEN -> ToolExecutorResult.Completed(actionOutput(ST_ACT_STALE, out))
        ActionStatus.NO_TAB -> ToolExecutorResult.Failed("unknown tab: no such browser tab", sideEffectFree = true)
        ActionStatus.TIMED_OUT -> ToolExecutorResult.TimedOut
        ActionStatus.ERROR -> ToolExecutorResult.Failed(bounded(out.reason))
    }

private fun actionOutput(
    status: String,
    out: ActionOutcome,
): JsonObject =
    buildJsonObject {
        put("status", JsonPrimitive(status))
        put("nodeIndex", JsonPrimitive(out.nodeIndex))
        put("tag", JsonPrimitive(out.tag))
        put("role", JsonPrimitive(out.role))
        put("reason", JsonPrimitive(bounded(out.reason)))
    }

// ===========================================================================
// browser.scroll
// ===========================================================================
object BrowserScrollTool {
    const val NAME: String = "browser.scroll"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Scroll a browser tab's viewport by dx (horizontal) and dy (vertical) CSS pixels. " +
                    "Negative dy scrolls up; positive dy scrolls down.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to scroll."))
                            put("dx", integerSchema("Horizontal scroll in CSS pixels.", -MAX_SCROLL_PX, MAX_SCROLL_PX))
                            put(
                                "dy",
                                integerSchema(
                                    "Vertical scroll in px (positive = down).",
                                    -MAX_SCROLL_PX,
                                    MAX_SCROLL_PX,
                                ),
                            )
                        },
                    required = listOf("tabId", "dx", "dy"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("status", enumSchema(listOf(ST_SCROLLED), "scrolled."))
                            put("dx", integerSchema("The horizontal delta applied.", -MAX_SCROLL_PX, MAX_SCROLL_PX))
                            put("dy", integerSchema("The vertical delta applied.", -MAX_SCROLL_PX, MAX_SCROLL_PX))
                            put("reason", stringSchema(MAX_DETAIL_CHARS, "An explanatory note; empty on success."))
                        },
                    required = listOf("status", "dx", "dy", "reason"),
                ),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L1,
            timeout = 15.seconds,
            maxOutputBytes = 2048,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.scroll' arguments: 'tabId' must be a string",
                        )
                val dx =
                    intArg(call.args, "dx", -MAX_SCROLL_PX, MAX_SCROLL_PX)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.scroll' arguments: 'dx' must be an integer",
                        )
                val dy =
                    intArg(call.args, "dy", -MAX_SCROLL_PX, MAX_SCROLL_PX)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.scroll' arguments: 'dy' must be an integer",
                        )
                val out = bridge.scroll(tabId, dx, dy)
                return when (out.status) {
                    ScrollStatus.SCROLLED -> {
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("status", JsonPrimitive(ST_SCROLLED))
                                put("dx", JsonPrimitive(out.dx))
                                put("dy", JsonPrimitive(out.dy))
                                put("reason", JsonPrimitive(bounded(out.reason)))
                            },
                        )
                    }

                    ScrollStatus.NO_PAGE,
                    ScrollStatus.NO_TAB,
                    -> {
                        ToolExecutorResult.Failed(
                            bounded(out.reason).ifEmpty { "no scrollable page in that tab" },
                            sideEffectFree = true,
                        )
                    }

                    ScrollStatus.TIMED_OUT -> {
                        ToolExecutorResult.TimedOut
                    }

                    ScrollStatus.ERROR -> {
                        ToolExecutorResult.Failed(bounded(out.reason))
                    }
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// browser.screenshot
// ===========================================================================
object BrowserScreenshotTool {
    const val NAME: String = "browser.screenshot"
    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Capture the current page of a browser tab as a PNG and save it to the Workspace. " +
                    "Returns the model-safe Workspace reference plus the file size and SHA-256 (never a " +
                    "raw filesystem path).",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to capture."))
                        },
                    required = listOf("tabId"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("status", enumSchema(listOf(ST_SAVED), "saved."))
                            put(
                                "reference",
                                stringSchema(MAX_URL, "Model-safe Workspace reference (scope:<id>:<path>)."),
                            )
                            put("sizeBytes", integerSchema("The PNG size in bytes.", 0, null))
                            put("sha256", stringSchema(64, "The lowercase hex SHA-256 of the saved PNG."))
                            put("reason", stringSchema(MAX_DETAIL_CHARS, "An explanatory note; empty on success."))
                        },
                    required = listOf("status", "reference", "sizeBytes", "sha256", "reason"),
                ),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L1,
            timeout = 45.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser.screenshot' arguments: 'tabId' must be a string",
                        )
                val out = bridge.screenshot(tabId)
                return when (out.status) {
                    ScreenshotStatus.SAVED -> {
                        ToolExecutorResult.Completed(
                            output =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_SAVED))
                                    put("reference", JsonPrimitive(out.reference))
                                    put("sizeBytes", JsonPrimitive(out.sizeBytes))
                                    put("sha256", JsonPrimitive(out.sha256))
                                    put("reason", JsonPrimitive(bounded(out.reason)))
                                },
                            auditDetail =
                                buildJsonObject {
                                    put("status", JsonPrimitive(ST_SAVED))
                                    put("reference", JsonPrimitive(out.reference))
                                    put("sizeBytes", JsonPrimitive(out.sizeBytes))
                                    put("sha256", JsonPrimitive(out.sha256))
                                },
                        )
                    }

                    ScreenshotStatus.NO_PAGE,
                    ScreenshotStatus.NO_TAB,
                    -> {
                        ToolExecutorResult.Failed(
                            bounded(out.reason).ifEmpty { "no captured page in that tab" },
                            sideEffectFree = true,
                        )
                    }

                    ScreenshotStatus.TIMED_OUT -> {
                        ToolExecutorResult.TimedOut
                    }

                    ScreenshotStatus.ERROR -> {
                        ToolExecutorResult.Failed(bounded(out.reason))
                    }
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

// ===========================================================================
// Registration
// ===========================================================================
object BrowserTools {
    /**
     * Registers all 11 `browser.*` contracts and implementations against the shared [bridge].
     * Called once from the app container (which owns the production `BrowserToolBridgeImpl`);
     * tests build a [ToolRegistry] / [ToolImplementationRegistry] pair and a fake bridge.
     */
    fun registerAll(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        BrowserOpenTool.register(registry, implementations, bridge)
        BrowserNavigateTool.register(registry, implementations, bridge)
        BrowserBackTool.register(registry, implementations, bridge)
        BrowserForwardTool.register(registry, implementations, bridge)
        BrowserReloadTool.register(registry, implementations, bridge)
        BrowserSnapshotTool.register(registry, implementations, bridge)
        BrowserFindTool.register(registry, implementations, bridge)
        BrowserClickTool.register(registry, implementations, bridge)
        BrowserTypeTool.register(registry, implementations, bridge)
        BrowserScrollTool.register(registry, implementations, bridge)
        BrowserScreenshotTool.register(registry, implementations, bridge)
    }
}
