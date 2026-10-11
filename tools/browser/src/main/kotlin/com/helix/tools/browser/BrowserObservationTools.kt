package com.helix.tools.browser

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

object BrowserTabsTool {
    const val NAME = "browser.tabs"
    const val VERSION = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "List up to 32 tabs opened by this session, or the exact trusted browser target scope. " +
                    "Other sessions and unscoped manual tabs are excluded. Does not load or activate pages. " +
                    "Metadata only: URL/title do not prove current page text, even when a URL embeds HTML. " +
                    "Use browser.snapshot to read page content. Empty tabs are not actionable pages.",
            inputSchema = objectSchema(buildJsonObject {}, emptyList()),
            outputSchema = tabsOutputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 5.seconds,
            maxOutputBytes = 256L * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
        )

    private fun tabsOutputSchema(): JsonObject =
        objectSchema(
            buildJsonObject {
                put("hostAvailable", booleanSchema("An Activity-owned browser host is available."))
                put("truncated", booleanSchema("More permitted tabs exist than returned."))
                put(
                    "tabs",
                    buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        put("maxItems", JsonPrimitive(32))
                        put(
                            "items",
                            tabSchema(),
                        )
                    },
                )
            },
            listOf("tabs", "hostAvailable", "truncated"),
        )

    private fun tabSchema(): JsonObject =
        objectSchema(
            buildJsonObject {
                put("tabId", stringSchema(MAX_TAB_ID, "Exact tab identity."))
                put(
                    "url",
                    stringSchema(
                        BrowserTabView.MAX_URL_CHARS,
                        "Bounded permitted URL prefix; untrusted data.",
                    ),
                )
                put(
                    "urlTruncated",
                    booleanSchema("URL prefix shortened; never treat it as the complete URL."),
                )
                put("titleTruncated", booleanSchema("Title prefix shortened."))
                put(
                    "title",
                    stringSchema(
                        BrowserTabView.MAX_TITLE_CHARS,
                        "Bounded title prefix; untrusted data.",
                    ),
                )
                put("selected", booleanSchema("Selected manual browser tab."))
                put(
                    "pageState",
                    enumSchema(
                        listOf("READY", "LOADING", "ERROR", "NO_PAGE"),
                        "Actual page state.",
                    ),
                )
                put("hostAvailable", booleanSchema("This tab has a live host."))
                put(
                    "navigationGeneration",
                    integerSchema("Committed page generation.", 0, null),
                )
            },
            listOf(
                "tabId",
                "url",
                "title",
                "selected",
                "pageState",
                "hostAvailable",
                "navigationGeneration",
                "urlTruncated",
                "titleTruncated",
            ),
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount") // cancellation and validation fail before the read-only host call
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                if (Instant.now() >=
                    call.deadline
                ) {
                    return ToolExecutorResult.Failed("deadline expired", sideEffectFree = true)
                }
                if (call.args.isNotEmpty()) {
                    return ToolExecutorResult.Failed(
                        "browser.tabs takes no arguments",
                        sideEffectFree = true,
                    )
                }
                val out = bridge.tabs(BrowserObservationContext(call.sessionId, call.authorizationScopeRef))
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("hostAvailable", JsonPrimitive(out.hostAvailable))
                        put("truncated", JsonPrimitive(out.truncated))
                        put(
                            "tabs",
                            JsonArray(
                                out.tabs.map { tab ->
                                    buildJsonObject {
                                        put("tabId", JsonPrimitive(tab.tabId))
                                        put("url", JsonPrimitive(tab.url))
                                        put("title", JsonPrimitive(tab.title))
                                        put("selected", JsonPrimitive(tab.selected))
                                        put("pageState", JsonPrimitive(tab.pageState))
                                        put("hostAvailable", JsonPrimitive(tab.hostAvailable))
                                        put("navigationGeneration", JsonPrimitive(tab.navigationGeneration))
                                        put("urlTruncated", JsonPrimitive(tab.urlTruncated))
                                        put("titleTruncated", JsonPrimitive(tab.titleTruncated))
                                    }
                                },
                            ),
                        )
                    },
                )
            }
        }
}

object BrowserWaitTool {
    const val NAME = "browser.wait"
    const val VERSION = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Observe text PRESENT or ABSENT in the bounded semantic tree of a settled current document, " +
                    "For at most 10 seconds, within this session or exact trusted target scope. " +
                    "Navigation or host replacement invalidates " +
                    "the wait. Loading pages require a new observation after settling. " +
                    "Never clicks, replays actions, or evaluates model scripts. " +
                    "ABSENT cannot be confirmed on a truncated tree. Multiple matches are ambiguous.",
            inputSchema =
                objectSchema(
                    buildJsonObject {
                        put("tabId", stringSchema(MAX_TAB_ID, "Exact tab identity."))
                        put(
                            "query",
                            stringSchema(MAX_QUERY, "Case-insensitive semantic text substring.", minLength = 1),
                        )
                        put("condition", enumSchema(listOf("PRESENT", "ABSENT"), "Supported condition."))
                        put("timeoutMillis", integerSchema("Milliseconds, bounded by original deadline.", 1, 10000))
                    },
                    listOf("tabId", "query", "condition", "timeoutMillis"),
                ),
            outputSchema = waitOutputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 15.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
        )

    private fun waitOutputSchema(): JsonObject =
        objectSchema(
            buildJsonObject {
                put(
                    "status",
                    enumSchema(
                        listOf(
                            "SATISFIED",
                            "TIMED_OUT",
                            "NO_PAGE",
                            "OUT_OF_SCOPE",
                            "PAGE_CHANGED",
                            "HOST_UNAVAILABLE",
                        ),
                        "Observed terminal state.",
                    ),
                )
                put("backend", enumSchema(listOf("WEBVIEW_DOM"), "Actual observation backend."))
                put(
                    "matchCount",
                    integerSchema("Observed match count when satisfied; omitted otherwise.", 0, MAX_NODES),
                )
                put("ambiguous", booleanSchema("More than one observed match when satisfied."))
                put("truncated", booleanSchema("Semantic tree or returned matches truncated."))
                put(
                    "token",
                    stringSchema(MAX_TOKEN, "Unique untruncated PRESENT token; revalidate before acting."),
                )
            },
            listOf("status", "backend"),
        )

    fun executor(bridge: BrowserToolBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val tabId = strArg(call.args, "tabId", MAX_TAB_ID)
                val query = strArg(call.args, "query", MAX_QUERY)?.takeIf { it.isNotBlank() }
                val condition =
                    BrowserWaitCondition.entries.firstOrNull {
                        it.name ==
                            strArg(
                                call.args,
                                "condition",
                                16,
                            )
                    }
                val timeout = intArg(call.args, "timeoutMillis", 1, 10000)?.takeIf { it in 1..10000 }
                if (call.args.keys != setOf("tabId", "query", "condition", "timeoutMillis")) {
                    return ToolExecutorResult.Failed("invalid browser.wait arguments", sideEffectFree = true)
                }
                if (tabId == null || query == null) {
                    return ToolExecutorResult.Failed("invalid browser.wait arguments", sideEffectFree = true)
                }
                if (condition == null || timeout == null) {
                    return ToolExecutorResult.Failed("invalid browser.wait arguments", sideEffectFree = true)
                }
                val out =
                    bridge.waitFor(
                        BrowserWaitRequest(
                            tabId,
                            query,
                            condition,
                            timeout,
                            BrowserObservationContext(call.sessionId, call.authorizationScopeRef),
                            call.deadline,
                            call.cancel::isCancelled,
                        ),
                    )
                if (out.status == BrowserWaitStatus.CANCELLED) return ToolExecutorResult.Cancelled
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(out.status.name))
                        put("backend", JsonPrimitive("WEBVIEW_DOM"))
                        out.observation?.let { found ->
                            put("matchCount", JsonPrimitive(found.matchCount))
                            put("ambiguous", JsonPrimitive(found.matchCount > 1))
                            put("truncated", JsonPrimitive(found.truncated))
                            if (condition == BrowserWaitCondition.PRESENT) {
                                found.uniqueCurrentToken()?.let { put("token", JsonPrimitive(it)) }
                            }
                        }
                    },
                )
            }
        }
}
