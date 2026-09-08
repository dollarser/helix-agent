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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

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
