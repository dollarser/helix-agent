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

    private data class SearchCandidate(
        val descriptor: ToolDescriptor,
        val exactName: Boolean,
        val allTerms: Boolean,
        val phraseInName: Boolean,
        val nameHits: Int,
        val matchedTerms: Int,
        val phraseInDescription: Boolean,
        val descriptionHits: Int,
    )

    @Synchronized
    fun search(
        sessionId: String,
        query: String,
        limit: Int,
        source: String? = null,
        category: String? = null,
    ): List<ToolDescriptor> {
        require(query.isNotBlank() && query.length <= 200)
        require(limit in 1..WINDOW)
        require(source == null || (source.isNotBlank() && source.length <= 200))
        require(category == null || (category.isNotBlank() && category.length <= 200))
        val normalizedQuery = query.trim().lowercase()
        val catalog = searchCatalog(normalizedQuery)
        val terms =
            normalizedQuery
                .split(Regex("[\\s,;]+"))
                .filter { it.isNotBlank() }
                .distinct()
        val matches =
            catalog
                .mapNotNull { descriptor ->
                    if (descriptor.name.value == "tools.search" ||
                        admittedWindows[sessionId]?.contains(descriptor) == false
                    ) {
                        return@mapNotNull null
                    }
                    val metadata = ToolDiscoveryMetadata.of(descriptor)
                    if (!metadata.matches(source, category)) return@mapNotNull null
                    candidate(descriptor, metadata, normalizedQuery, terms)
                }.sortedWith(
                    compareByDescending<SearchCandidate> { it.exactName }
                        .thenByDescending { it.allTerms }
                        .thenByDescending { it.phraseInName }
                        .thenByDescending { it.nameHits }
                        .thenByDescending { it.matchedTerms }
                        .thenByDescending { it.phraseInDescription }
                        .thenByDescending { it.descriptionHits }
                        .thenBy { it.descriptor.name.value },
                ).asSequence()
                .map { it.descriptor }
                // Availability can read durable session policy. Only inspect ranked matching
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

    private fun searchCatalog(query: String): List<ToolDescriptor> {
        val catalog = latest()
        val exact = catalog.filter { it.name.value.lowercase() == query }
        return exact.ifEmpty { catalog }
    }

    private fun ToolDiscoveryMetadata.matches(
        source: String?,
        category: String?,
    ): Boolean {
        val sourceMatches = source == null || this.source.equals(source.trim(), ignoreCase = true)
        val categoryMatches = category == null || this.category.equals(category.trim(), ignoreCase = true)
        return sourceMatches && categoryMatches
    }

    private fun candidate(
        descriptor: ToolDescriptor,
        metadata: ToolDiscoveryMetadata,
        query: String,
        terms: List<String>,
    ): SearchCandidate? {
        val name = descriptor.name.value.lowercase()
        val description = descriptor.description.lowercase() + " " + metadata.searchText
        val nameHits = terms.count { it in name }
        val descriptionHits = terms.count { it !in name && it in description }
        val matchedTerms = nameHits + descriptionHits
        if (matchedTerms == 0) return null
        return SearchCandidate(
            descriptor,
            name == query,
            matchedTerms == terms.size,
            query in name,
            nameHits,
            matchedTerms,
            query in description,
            descriptionHits,
        )
    }

    fun visible(
        sessionId: String,
        admitted: List<ToolDescriptor>,
        defaultNames: Set<String> = emptySet(),
    ): List<ToolDescriptor> {
        // One availability read per candidate, outside the shared discovery-window lock.
        // Execution still performs its independent live authorization check.
        val available = admitted.filter { availability(sessionId, it) }
        val selected =
            synchronized(this) {
                admittedWindows[sessionId] = available.toSet()
                while (admittedWindows.size > MAX_SESSIONS) admittedWindows.remove(admittedWindows.keys.first())
                loaded[sessionId].orEmpty().filter { it in available }.also { current ->
                    loaded[sessionId]?.let { loaded[sessionId] = current }
                }
            }
        // A disable that landed while a tool was in the session window removes it here too —
        // the window replacement re-reads the same shared predicate (ADR section 1.1).
        val mcp = available.filter { it.origin is ToolOrigin.McpOrigin }
        val local =
            available.filter {
                it.origin !is ToolOrigin.McpOrigin &&
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

    /** Same admitted snapshot as this request's schemas; metadata is data, never permission. */
    fun catalog(sessionId: String): String? {
        val admitted = synchronized(this) { admittedWindows[sessionId]?.toList().orEmpty() }
        val rows =
            admitted
                .filter { availability(sessionId, it) }
                .map(ToolDiscoveryMetadata::of)
                .groupBy { it.source }
                .toSortedMap()
                .map { (source, tools) ->
                    buildJsonObject {
                        put("source", JsonPrimitive(source))
                        put(
                            "categories",
                            JsonArray(
                                tools
                                    .map { it.category }
                                    .distinct()
                                    .sorted()
                                    .map(::JsonPrimitive),
                            ),
                        )
                    }.toString()
                }
        if (rows.isEmpty()) return null
        val included = mutableListOf<String>()
        var bytes = 0
        for (row in rows) {
            val size = row.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > CATALOG_BYTES) break
            included += row
            bytes += size
        }
        return "[TOOL SOURCES: metadata only; use tools.search for callable schemas]\n" +
            included.joinToString("\n") +
            (if (included.size < rows.size) "\nMore sources are searchable; directory summary truncated." else "") +
            "\n[/TOOL SOURCES]"
    }

    fun register(
        registry: ToolRegistry,
        metadata: (ToolExecutor) -> ToolExecutor = { it },
    ) {
        val descriptor =
            ToolDescriptor(
                name = ToolName("tools.search"),
                version = ToolVersion(2),
                description =
                    "Search user-enabled tools by name, description, source, fields or common Chinese aliases: " +
                        "files, browser, Android UI, Linux, Skills, " +
                        "connectors, MCP and A2A. Search when a needed tool is absent; " +
                        "combine concise keywords for related capabilities, or use its exact name for one tool. " +
                        "Exact-name queries return only that tool. " +
                        "Use returned tools from the next request's schemas; " +
                        "do not guess arguments or repeat a successful search. " +
                        "Matches REPLACE the bounded session discovery window, not accumulate. " +
                        "Do not issue multiple searches in one response; later results can discard earlier ones. " +
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
            metadata(
                object : ToolExecutor {
                    @Suppress("ReturnCount") // Cancellation and missing local context are distinct terminal results.
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                        val session =
                            call.sessionId ?: return ToolExecutorResult.Failed("MCP_DISCOVERY_SESSION_REQUIRED")
                        val query =
                            call.args
                                .getValue("query")
                                .jsonPrimitive.content
                        val limit = call.args["limit"]?.jsonPrimitive?.intOrNull ?: 8
                        if (query.isBlank()) return ToolExecutorResult.Failed("MCP_DISCOVERY_QUERY_REQUIRED")
                        val matches =
                            search(
                                session,
                                query,
                                limit,
                                call.args["source"]?.jsonPrimitive?.content,
                                call.args["category"]?.jsonPrimitive?.content,
                            )
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
                                                val metadata = ToolDiscoveryMetadata.of(tool)
                                                put("source", JsonPrimitive(metadata.source))
                                                put("category", JsonPrimitive(metadata.category))
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
                },
            ),
        )
    }

    companion object {
        const val WINDOW = 16
        private const val MAX_SESSIONS = 128
        private const val CATALOG_BYTES = 4096

        @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
        private const val INPUT = """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":200},"limit":{"type":"integer","minimum":1,"maximum":16},"source":{"type":"string","minLength":1,"maxLength":200},"category":{"type":"string","minLength":1,"maxLength":200}},"required":["query"],"additionalProperties":false}"""

        @Suppress("ktlint:standard:max-line-length", "MaxLineLength")
        private const val OUTPUT = """{"type":"object","properties":{"tools":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"description":{"type":"string"},"version":{"type":"integer"},"source":{"type":"string"},"category":{"type":"string"}},"required":["name","description","version","source","category"],"additionalProperties":false}}},"required":["tools"],"additionalProperties":false}"""
    }
}
