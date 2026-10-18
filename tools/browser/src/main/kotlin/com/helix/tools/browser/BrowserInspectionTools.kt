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
import kotlin.time.Duration.Companion.seconds

object BrowserSnapshotTool {
    const val NAME: String = "browser.snapshot"
    const val VERSION: Int = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Capture a bounded semantic snapshot of a browser tab: its url, title, origin, " +
                    "navigation generation, tree fingerprint, and the semantic nodes (links, buttons, " +
                    "fields, images, headings). Each node carries a short-lived token that " +
                    "browser.click / browser.type consume. Those names are discovery hints: " +
                    "if the action is absent from this request's tool schemas, first call tools.search " +
                    "with its exact name, then use the schema from the following request. " +
                    "Taking a snapshot does not load action tools or authorize them.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("tabId", stringSchema(MAX_TAB_ID, "The tab to snapshot."))
                        },
                    required = listOf("tabId"),
                ),
            outputSchema = snapshotOutputSchema(),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 30.seconds,
            maxOutputBytes = 1024L * 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
        )

    private fun snapshotOutputSchema(): JsonObject =
        objectSchema(
            properties =
                buildJsonObject {
                    put("url", stringSchema(MAX_URL, "Bounded page URL prefix (UNTRUSTED)."))
                    put("title", stringSchema(MAX_NODE_TEXT, "Bounded page title prefix (UNTRUSTED)."))
                    put("urlTruncated", booleanSchema("URL prefix shortened; not a complete navigation target."))
                    put("titleTruncated", booleanSchema("Title prefix shortened."))
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
                    put("backend", enumSchema(listOf("WEBVIEW_DOM"), "Actual semantic observation backend."))
                    put(
                        "observedAtMillis",
                        integerSchema("Host observation time, epoch milliseconds.", 0, null),
                    )
                },
            required =
                listOf(
                    "url",
                    "title",
                    "urlTruncated",
                    "titleTruncated",
                    "origin",
                    "navigationGeneration",
                    "fingerprint",
                    "truncated",
                    "nodeCount",
                    "nodes",
                    "backend",
                    "observedAtMillis",
                ),
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
                val url = metadataPrefix(out.url, MAX_URL)
                val title = metadataPrefix(out.title, MAX_NODE_TEXT)
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("url", JsonPrimitive(url))
                        put("title", JsonPrimitive(title))
                        put("urlTruncated", JsonPrimitive(url != out.url))
                        put("titleTruncated", JsonPrimitive(title != out.title))
                        put("origin", JsonPrimitive(out.origin))
                        put("navigationGeneration", JsonPrimitive(out.navigationGeneration))
                        put("fingerprint", JsonPrimitive(out.fingerprint))
                        put("truncated", JsonPrimitive(out.truncated))
                        put("nodeCount", JsonPrimitive(out.nodeCount))
                        put("nodes", JsonArray(out.nodes.map { nodeObject(it) }))
                        put("backend", JsonPrimitive("WEBVIEW_DOM"))
                        put("observedAtMillis", JsonPrimitive(out.observedAtMillis))
                    },
                )
            }
        }

    private fun metadataPrefix(
        value: String,
        limit: Int,
    ): String = value.take(limit).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
}

// ===========================================================================
// browser.find
// ===========================================================================
object BrowserFindTool {
    const val NAME: String = "browser.find"
    const val VERSION: Int = 1

    fun descriptor(origin: ToolOrigin.PluginOrigin): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Search the most recent browser.snapshot of a tab for a case-insensitive substring " +
                    "and return bounded matches, total observed match count, ambiguity, truncation and expiry. " +
                    "Refresh expired observations. Truncated results do not prove uniqueness.",
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
                            put(
                                "matchCount",
                                integerSchema("Total matches within the bounded observed tree.", 0, MAX_NODES),
                            )
                            put("matches", matchesArraySchema())
                            put("ambiguous", booleanSchema("More than one observed match."))
                            put(
                                "truncated",
                                booleanSchema("Tree or returned matches were truncated; uniqueness is unproven."),
                            )
                            put(
                                "observationExpired",
                                booleanSchema("Known observation expiry; false does not prove DOM unchanged."),
                            )
                            put("backend", enumSchema(listOf("WEBVIEW_DOM"), "Actual observation backend."))
                        },
                    required =
                        listOf(
                            "query",
                            "matchCount",
                            "matches",
                            "ambiguous",
                            "truncated",
                            "observationExpired",
                            "backend",
                        ),
                ),
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 10.seconds,
            maxOutputBytes = 32L * 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = origin,
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
                        put("ambiguous", JsonPrimitive(out.matchCount > 1))
                        put("truncated", JsonPrimitive(out.truncated))
                        put("observationExpired", JsonPrimitive(out.observationExpired))
                        put("backend", JsonPrimitive("WEBVIEW_DOM"))
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
}
