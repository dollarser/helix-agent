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
import kotlin.time.Duration.Companion.seconds

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
