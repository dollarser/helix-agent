package com.helix.app.mcp

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
    /**
     * HXA-209 B3: the shared disabled-tool predicate (ADR section 1.1) — the SAME instance the
     * model schema and the execution entry use, so a disable is removed from search and the
     * loaded window at the same moment it is refused at the dispatcher.
     */
    private val availability: (sessionId: String, descriptor: ToolDescriptor) -> Boolean = { _, _ -> true },
) {
    private val admittedWindows = LinkedHashMap<String, Set<ToolDescriptor>>(16, 0.75f, true)
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
                    descriptor.name.value != "tools.search" &&
                        (admittedWindows[sessionId]?.contains(descriptor) != false) &&
                        words.all {
                            it in "${descriptor.name.value} ${descriptor.description}".lowercase()
                        }
                }.sortedWith(
                    compareBy<ToolDescriptor> { it.name.value.lowercase() != query.trim().lowercase() }
                        .thenBy { descriptor -> words.count { it !in descriptor.name.value.lowercase() } }
                        .thenBy { it.name.value },
                ).asSequence()
                // Availability can read durable session policy. Only inspect matching
                // candidates, stopping when the bounded result window is full.
                .filter { availability(sessionId, it) }
                .take(limit)
                .toList()
        // Successful searches replace the bounded window. A miss must not erase useful work;
        // prune stale/disabled/unadmitted entries even when no replacement was found.
        loaded[sessionId] =
            matches.ifEmpty {
                val current = latest().toSet()
                loaded[sessionId].orEmpty().filter {
                    it in current && availability(sessionId, it) && admittedWindows[sessionId]?.contains(it) != false
                }
            }
        while (loaded.size > MAX_SESSIONS) loaded.remove(loaded.keys.first())
        return matches
    }

    @Synchronized
    fun visible(
        sessionId: String,
        admitted: List<ToolDescriptor>,
        defaultNames: Set<String> = emptySet(),
    ): List<ToolDescriptor> {
        admittedWindows[sessionId] = admitted.filter { availability(sessionId, it) }.toSet()
        while (admittedWindows.size > MAX_SESSIONS) admittedWindows.remove(admittedWindows.keys.first())
        val selected = loaded[sessionId].orEmpty().filter { it in admitted && availability(sessionId, it) }
        loaded[sessionId]?.let { loaded[sessionId] = selected }
        // A disable that landed while a tool was in the session window removes it here too —
        // the window replacement re-reads the same shared predicate (ADR section 1.1).
        val mcp = admitted.filter { it.origin is ToolOrigin.McpOrigin && availability(sessionId, it) }
        val local =
            admitted.filter {
                it.origin !is ToolOrigin.McpOrigin && availability(sessionId, it) &&
                    (it.name.value == "tools.search" || it.name.value in defaultNames)
            }
        val discovery = local.filter { it.name.value == "tools.search" }
        // ChatService truncates this list to the model limit. Keep discovery and its
        // current results reachable; optional local tools use the same bounded discovery window.
        return (discovery + selected + local + if (mcp.size <= WINDOW) mcp else emptyList())
            .distinctBy { it.name }
    }

    private fun latest(): List<ToolDescriptor> =
        registry
            .all()
            .groupBy { it.name }
            .values
            .map { it.maxBy { version -> version.version.value } }

    fun register(registry: ToolRegistry) {
        val descriptor =
            ToolDescriptor(
                name = ToolName("tools.search"),
                version = ToolVersion(1),
                description =
                    "Search user-enabled tools by name or description: files, browser, Android UI, Linux, Skills, " +
                        "connectors, MCP and A2A. Search when a needed tool is absent; " +
                        "use concise keywords or its name. " +
                        "Matches replace the bounded session discovery window; " +
                        "a miss preserves still-available tools. " +
                        "Discovery grants no execution permission.",
                inputSchema = Json.parseToJsonElement(INPUT).jsonObject,
                outputSchema = Json.parseToJsonElement(OUTPUT).jsonObject,
                operationClass = ToolOperationClass.READ_ONLY,
                timeout = 5.seconds,
                maxOutputBytes = 16 * 1024L,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )

        registry.register(
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
