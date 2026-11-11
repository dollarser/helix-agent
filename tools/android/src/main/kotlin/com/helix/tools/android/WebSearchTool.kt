package com.helix.tools.android

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

enum class WebSearchProvider {
    TAVILY,
    DEEPSEEK,
}

/** Provider and credential are one snapshot, so switching providers cannot reuse the old key. */
data class WebSearchConfiguration(
    val provider: WebSearchProvider,
    val key: String,
)

/** The host supplies an opted-in credential, never model arguments or tool metadata. */
fun interface WebSearchCredential {
    fun configuredSearch(): WebSearchConfiguration?
}

internal fun interface WebSearchTransport {
    fun search(
        query: String,
        configuration: WebSearchConfiguration,
        call: ExecutableToolCall,
    ): ByteArray
}

object WebSearchTool {
    const val NAME = "web_search"

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(1),
            description =
                "Search the public web using the explicitly configured Tavily Basic or DeepSeek Search API. " +
                    "Requires enabled Web Search settings and a provider-specific key; consumes provider credits. " +
                    "DeepSeek queries must be at most 128 Unicode characters. " +
                    "Returns untrusted snippets and source URLs, not verified page contents. " +
                    "Use web_fetch to verify claims. " +
                    "Never automatically retry an uncertain search; it may have consumed quota.",
            inputSchema = inputSchema(),
            outputSchema =
                objectSchema(
                    buildJsonObject {
                        put("provider", stringSchema(32, "Search provider."))
                        put(
                            "truncated",
                            androidSystemToolsBooleanSchema("Helix truncated results, titles or snippets."),
                        )
                        put(
                            "results",
                            buildJsonObject {
                                put("type", "array")
                                put("maxItems", 5)
                                put(
                                    "items",
                                    objectSchema(
                                        buildJsonObject {
                                            put("title", stringSchema(512, "Untrusted result title."))
                                            put("url", stringSchema(WEB_URL_LIMIT, "Unfetched, unverified source URL."))
                                            put("snippet", stringSchema(4000, "Untrusted search snippet."))
                                        },
                                        listOf("title", "url", "snippet"),
                                    ),
                                )
                            },
                        )
                    },
                    listOf("provider", "results", "truncated"),
                ),
            operationClass = ToolOperationClass.NETWORK,
            timeout = 30.seconds,
            maxOutputBytes = 128L * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun inputSchema(): JsonObject =
        objectSchema(
            buildJsonObject {
                put(
                    "query",
                    stringSchema(
                        2000,
                        "Search query sent to the configured provider. DeepSeek limit: 128 Unicode characters.",
                    ),
                )
            },
            listOf("query"),
        )

    fun register(
        registry: ToolRegistry,
        policy: EgressPolicyProvider,
        credential: WebSearchCredential,
    ) {
        val transport = PinnedWebFetchTransport(policy)
        registry.register(
            descriptor(),
            executor(credential) { query, configuration, call ->
                val request = request(query, configuration)
                transport.postJson(request, call.deadline.toEpochMilli(), call.cancel) {
                    if (credential.configuredSearch() !=
                        configuration
                    ) {
                        throw WebFetchFailure("configuration-changed-before-send")
                    }
                }
            },
        )
    }

    internal fun request(
        query: String,
        configuration: WebSearchConfiguration,
    ): Request {
        val payload =
            buildJsonObject {
                put("query", query)
                when (configuration.provider) {
                    WebSearchProvider.TAVILY -> {
                        put("search_depth", "basic")
                        put("max_results", 5)
                        put("include_answer", false)
                        put("include_raw_content", false)
                        put("auto_parameters", false)
                    }

                    WebSearchProvider.DEEPSEEK -> {
                        put("count", 5)
                        put("need_content", false)
                    }
                }
            }
        return Request
            .Builder()
            .url(
                when (configuration.provider) {
                    WebSearchProvider.TAVILY -> "https://api.tavily.com/search"
                    WebSearchProvider.DEEPSEEK -> "https://api.deepseek.com/v1/tools/search"
                },
            ).header("Authorization", "Bearer ${configuration.key}")
            .header("Accept-Encoding", "identity")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    internal fun executor(
        credential: WebSearchCredential,
        transport: WebSearchTransport,
    ): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ThrowsCount") // Input, opt-in and credential reflection are distinct fail-closed guards.
            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                try {
                    checkWebFetchActive(call.deadline.toEpochMilli(), call.cancel)
                    val query =
                        strArg(call.args, "query", 2000)?.takeIf { it.isNotBlank() }
                            ?: throw WebFetchFailure("invalid-query")
                    val configuration =
                        credential.configuredSearch()?.takeIf { it.key.isNotBlank() }
                            ?: throw WebFetchFailure(
                                "not-configured: enable Web Search and save a key " +
                                    "for the selected provider in Settings",
                            )
                    if (configuration.provider == WebSearchProvider.DEEPSEEK &&
                        query.codePointCount(0, query.length) > 128
                    ) {
                        throw WebFetchFailure("deepseek-query-too-long: maximum 128 Unicode characters")
                    }
                    val key = configuration.key
                    val bytes = transport.search(query, configuration, call)
                    checkWebFetchActive(call.deadline.toEpochMilli(), call.cancel)
                    if (bytes.toString(Charsets.UTF_8).contains(key)) {
                        throw WebFetchFailure("credential-reflected-in-response")
                    }
                    ToolExecutorResult.Completed(parseResults(bytes, configuration))
                } catch (failure: WebFetchFailure) {
                    when (failure.code) {
                        "cancelled" -> ToolExecutorResult.Cancelled
                        "timed-out" -> ToolExecutorResult.TimedOut
                        else -> ToolExecutorResult.Failed("web_search: ${failure.code}")
                    }
                } catch (_: IOException) {
                    transportFailure(call)
                } catch (_: IllegalArgumentException) {
                    ToolExecutorResult.Failed("web_search: invalid-provider-response-or-configuration")
                }
        }

    private fun transportFailure(call: ExecutableToolCall): ToolExecutorResult =
        when {
            call.cancel.isCancelled() || Thread.currentThread().isInterrupted -> {
                ToolExecutorResult.Cancelled
            }

            System.currentTimeMillis() >= call.deadline.toEpochMilli() -> {
                ToolExecutorResult.TimedOut
            }

            else -> {
                ToolExecutorResult.Failed(
                    "web_search: transport-error; quota outcome unknown, do not automatically retry",
                )
            }
        }

    @Suppress("ThrowsCount") // Malformed provider structure and unsafe source URLs fail closed.
    internal fun parseResults(bytes: ByteArray, configuration: WebSearchConfiguration): JsonObject {
        val key = configuration.key
        val snippetField = if (configuration.provider == WebSearchProvider.DEEPSEEK) "summary" else "content"
        require(bytes.size <= WEB_BODY_LIMIT)
        val text = bytes.toString(Charsets.UTF_8)
        requireBoundedNesting(text)
        val root =
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw WebFetchFailure("invalid-provider-response")
        val rows = root["results"] as? JsonArray ?: throw WebFetchFailure("invalid-provider-response")
        return buildJsonObject {
            put("provider", if (configuration.provider == WebSearchProvider.DEEPSEEK) "deepseek" else "tavily-basic")
            put(
                "truncated",
                rows.size > 5 ||
                    rows.take(5).any {
                        val row = it as? JsonObject ?: throw WebFetchFailure("invalid-provider-response")
                        field(row, "title").length > 512 || field(row, snippetField).length > 4000
                    },
            )
            put(
                "results",
                JsonArray(
                    rows.take(5).map { row ->
                        val item = row as? JsonObject ?: throw WebFetchFailure("invalid-provider-response")
                        val title = field(item, "title")
                        val snippet = field(item, snippetField)
                        val source = field(item, "url")
                        if (listOf(title, snippet, source).any { it.contains(key) }) {
                            throw WebFetchFailure("credential-reflected-in-response")
                        }
                        val url = webFetchUrl(source).toString()
                        buildJsonObject {
                            put("title", unicodePrefix(title, 512))
                            put("url", url)
                            put("snippet", unicodePrefix(snippet, 4000))
                        }
                    },
                ),
            )
        }
    }

    /** Iterative preflight prevents an attacker-controlled JSON nesting stack before parsing. */
    private fun requireBoundedNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                !quoted && (char == '{' || char == '[') -> depth++
                !quoted && (char == '}' || char == ']') -> depth--
            }
            if (depth > 16) throw WebFetchFailure("provider-json-too-deep")
        }
    }

    private fun field(
        item: JsonObject,
        name: String,
    ): String =
        (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: throw WebFetchFailure("invalid-provider-response")
}
