package com.helix.app.mcp

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/** Exposure cache only: discovery cannot register remote tools or authorize their execution. */
internal class McpToolDiscovery(
    private val registry: ToolRegistry,
) {
    private val loaded = LinkedHashMap<String, List<ToolDescriptor>>(16, 0.75f, true)

    @Synchronized
    fun search(
        sessionId: String,
        query: String,
        limit: Int,
    ): List<ToolDescriptor> {
        require(query.isNotBlank() && query.length <= 200)
        require(limit in 1..WINDOW)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matches =
            latest()
                .filter { descriptor ->
                    descriptor.origin is ToolOrigin.McpOrigin &&
                        words.all {
                            it in "${descriptor.name.value} ${descriptor.description}".lowercase()
                        }
                }.sortedBy { it.name.value }
                .take(limit)
        // A fresh bounded window, rather than ever-growing schemas across the conversation.
        loaded[sessionId] = matches
        while (loaded.size > MAX_SESSIONS) loaded.remove(loaded.keys.first())
        return matches
    }

    @Synchronized
    fun visible(
        sessionId: String,
        admitted: List<ToolDescriptor>,
    ): List<ToolDescriptor> {
        val selected = loaded[sessionId].orEmpty().filter { it in admitted }
        loaded[sessionId]?.let { loaded[sessionId] = selected }
        val mcp = admitted.filter { it.origin is ToolOrigin.McpOrigin }
        return admitted.filter { it.origin !is ToolOrigin.McpOrigin } +
            if (mcp.size <= WINDOW) mcp else selected
    }

    private fun latest(): List<ToolDescriptor> =
        registry
            .all()
            .groupBy { it.name }
            .values
            .map { it.maxBy { version -> version.version.value } }

    fun register(implementations: ToolImplementationRegistry) {
        val descriptor =
            ToolDescriptor(
                name = ToolName("tools.search"),
                version = ToolVersion(1),
                description =
                    "Search user-enabled MCP tools by name or description. For large catalogs, call this first; " +
                        "matched schemas replace the session MCP window on the next request. " +
                        "Discovery grants no execution permission.",
                inputSchema = Json.parseToJsonElement(INPUT).jsonObject,
                outputSchema = Json.parseToJsonElement(OUTPUT).jsonObject,
                operationClass = ToolOperationClass.READ_ONLY,
                baseRisk = RiskLevel.L0,
                timeout = 5.seconds,
                maxOutputBytes = 16 * 1024L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        registry.register(descriptor)
        implementations.register(
            descriptor,
            object : ToolExecutor {
                @Suppress("ReturnCount") // Cancellation and missing local context are distinct terminal results.
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                    val session = call.sessionId ?: return ToolExecutorResult.Failed("MCP_DISCOVERY_SESSION_REQUIRED")
                    val query =
                        call.args
                            .getValue("query")
                            .jsonPrimitive.content
                    val limit = call.args["limit"]?.jsonPrimitive?.intOrNull ?: 8
                    if (query.isBlank()) return ToolExecutorResult.Failed("MCP_DISCOVERY_QUERY_REQUIRED")
                    val matches = search(session, query, limit)
                    return ToolExecutorResult.Completed(
                        buildJsonObject {
                            put(
                                "tools",
                                JsonArray(
                                    matches.map { tool ->
                                        buildJsonObject {
                                            put("name", JsonPrimitive(tool.name.value))
                                            put("description", JsonPrimitive(tool.description.take(240)))
                                            put("version", JsonPrimitive(tool.version.value))
                                        }
                                    },
                                ),
                            )
                        },
                    )
                }
            },
        )
    }

    companion object {
        const val WINDOW = 16
        private const val MAX_SESSIONS = 128

        @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
        private const val INPUT = """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":200},"limit":{"type":"integer","minimum":1,"maximum":16}},"required":["query"],"additionalProperties":false}"""

        @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
        private const val OUTPUT = """{"type":"object","properties":{"tools":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"version":{"type":"integer"}},"required":["name","description","version"],"additionalProperties":false}}},"required":["tools"],"additionalProperties":false}"""
    }
}
