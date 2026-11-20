package com.helix.tools.browser

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

object BrowserOpenTool {
    const val NAME: String = "browser_open"
    const val VERSION: Int = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Open a new Helix in-app browser tab, select it, and navigate it to an http/https " +
                    "URL. Returns the new tab id; pass it to the other browser tools. An empty " +
                    "url opens a blank tab. Use browser_navigate to reuse an existing tab. " +
                    "Opening a tab does not prove page loading; use browser_snapshot to inspect it.",
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
                            put(
                                "url",
                                stringSchema(MAX_URL, "The requested navigation target, not proof the page loaded."),
                            )
                            put(
                                "origin",
                                stringSchema(MAX_ORIGIN, "Target origin (about:blank / data:opaque for non-http)."),
                            )
                        },
                    required = listOf("tabId", "url", "origin"),
                ),
            operationClass = ToolOperationClass.NETWORK,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
            discovery = ToolDiscoveryHints(category = "browser"),
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val url =
                    strArg(call.args, "url", MAX_URL)
                        ?: return ToolExecutorResult.Failed("invalid 'browser_open' arguments: 'url' must be a string")
                val out = bridge.open(url, call.sessionId)
                out.failureReason?.let { return ToolExecutorResult.Failed(it) }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("tabId", JsonPrimitive(out.tabId))
                        put("url", JsonPrimitive(out.url))
                        put("origin", JsonPrimitive(out.origin))
                    },
                )
            }
        }
}

// ===========================================================================
// browser_navigate
// ===========================================================================
object BrowserNavigateTool {
    const val NAME: String = "browser_navigate"
    const val VERSION: Int = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Navigate an existing browser tab to an http/https URL through the URL policy. " +
                    "Reports whether the navigation started, was denied by policy (with the reason), " +
                    "or the tab is unknown. Use browser_snapshot after navigation to inspect actual page content.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "tabId",
                                stringSchema(
                                    MAX_TAB_ID,
                                    "Exact tabId returned by browser_tabs, browser_open or browser_search.",
                                ),
                            )
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
                            put(
                                "url",
                                stringSchema(
                                    MAX_URL,
                                    "The requested navigation target; empty on denial, not proof of page loading.",
                                ),
                            )
                            put("origin", stringSchema(MAX_ORIGIN, "The target origin; empty on denial."))
                            put(
                                "reason",
                                stringSchema(MAX_DETAIL_CHARS, "The policy denial reason; empty when started."),
                            )
                        },
                    required = listOf("status", "url", "origin", "reason"),
                ),
            operationClass = ToolOperationClass.NETWORK,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
            discovery = ToolDiscoveryHints(category = "browser"),
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser_navigate' arguments: 'tabId' must be a string",
                        )
                val url =
                    strArg(call.args, "url", MAX_URL)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser_navigate' arguments: 'url' must be a string",
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
}
