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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

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
