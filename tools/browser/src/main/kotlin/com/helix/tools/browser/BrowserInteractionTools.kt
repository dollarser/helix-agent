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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

object BrowserClickTool {
    const val NAME: String = "browser_click"
    const val VERSION: Int = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Click the node named by a short-lived token from the most recent browser_snapshot of " +
                    "a tab. Password, payment and one-time-code fields are refused; the token must " +
                    "still be valid (a navigation, refresh, DOM change or expiry makes it stale).",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab the token belongs to."))
                            put(
                                "token",
                                stringSchema(MAX_TOKEN, "The node token from the most recent browser_snapshot."),
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
                            "invalid 'browser_click' arguments: 'tabId' must be a string",
                        )
                val token =
                    strArg(call.args, "token", MAX_TOKEN)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser_click' arguments: 'token' must be a string",
                        )
                val out = bridge.click(tabId, token)
                return actionResult(out)
            }
        }
}

// ===========================================================================
// browser_type
// ===========================================================================
object BrowserTypeTool {
    const val NAME: String = "browser_type"
    const val VERSION: Int = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Set text in an editable field: replace its contents by default, or append with mode=append. " +
                    "Use a fresh browser_snapshot token. Sensitive fields are refused. " +
                    "This does not submit the form; use browser_submit and observe the result.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab the token belongs to."))
                            put(
                                "token",
                                stringSchema(MAX_TOKEN, "The node token from the most recent browser_snapshot."),
                            )
                            put(
                                "text",
                                stringSchema(MAX_TYPE_TEXT, "Literal text; empty clears in replace mode."),
                            )
                            put(
                                "mode",
                                enumSchema(listOf("replace", "append"), "Default replace; append keeps text."),
                            )
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
                val mode = if ("mode" in call.args) strArg(call.args, "mode", 16) else "replace"
                if (mode == "append") {
                    return BrowserNodeActionTool.executor(bridge, BrowserNodeAction.APPEND).execute(call)
                }
                if (mode != "replace") {
                    return ToolExecutorResult.Failed("mode must be replace or append", sideEffectFree = true)
                }
                val tabId =
                    strArg(call.args, "tabId", MAX_TAB_ID)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser_type' arguments: 'tabId' must be a string",
                        )
                val token =
                    strArg(call.args, "token", MAX_TOKEN)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser_type' arguments: 'token' must be a string",
                        )
                val text =
                    strArg(call.args, "text", MAX_TYPE_TEXT)
                        ?: return ToolExecutorResult.Failed("invalid 'browser_type' arguments: 'text' must be a string")
                val out = bridge.type(tabId, token, text)
                return actionResult(out)
            }
        }
}

/** Maps a closed [ActionOutcome] to the click/type result (shared by [BrowserClickTool] and [BrowserTypeTool]). */
internal fun actionResult(out: ActionOutcome): ToolExecutorResult =
    when (out.status) {
        ActionStatus.PERFORMED -> {
            ToolExecutorResult.Completed(actionOutput(ST_ACT_PERFORMED, out))
        }

        ActionStatus.REFUSED -> {
            ToolExecutorResult.Completed(actionOutput(ST_ACT_REFUSED, out))
        }

        ActionStatus.STALE_TOKEN -> {
            ToolExecutorResult.Completed(actionOutput(ST_ACT_STALE, out))
        }

        ActionStatus.NO_TAB -> {
            ToolExecutorResult.Failed("unknown tab: no such browser tab", sideEffectFree = true)
        }

        ActionStatus.TIMED_OUT -> {
            ToolExecutorResult.TimedOut
        }

        ActionStatus.UNKNOWN -> {
            ToolExecutorResult.Failed(
                "Browser action outcome is unknown; inspect the page before any new action. " +
                    "Do not replay automatically.",
                requiresReview = true,
            )
        }

        ActionStatus.ERROR -> {
            ToolExecutorResult.Failed(bounded(out.reason))
        }
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
// browser_scroll
// ===========================================================================
object BrowserScrollTool {
    const val NAME: String = "browser_scroll"
    const val VERSION: Int = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
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
            timeout = 15.seconds,
            maxOutputBytes = 2048,
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
                            "invalid 'browser_scroll' arguments: 'tabId' must be a string",
                        )
                val dx =
                    intArg(call.args, "dx", -MAX_SCROLL_PX, MAX_SCROLL_PX)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser_scroll' arguments: 'dx' must be an integer",
                        )
                val dy =
                    intArg(call.args, "dy", -MAX_SCROLL_PX, MAX_SCROLL_PX)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'browser_scroll' arguments: 'dy' must be an integer",
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
}
