package com.helix.app.provider

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.model.ToolName
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.openai.chat.ImageResolver
import com.helix.provider.openai.chat.OpenAiChatProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * HXA-027 self-hosted service smoke (developer instrumented test): drives the REAL
 * provider stack — [OpenAiChatProvider] + [OkHttpWireClient] — from the emulator
 * through the host bridge `10.0.2.2` (provider doc 2.5) against the dev-machine
 * Ollama server. Verifies the minimal text stream and the minimal ToolCall, and
 * records the server's capabilities/unsupported fields (doc: “明确记录不支持字段”).
 *
 * The test is assumption-guarded: when no model server listens on the bridge
 * (CI without a local server, or the dev machine restarted without Ollama) every
 * test is SKIPPED with a reason instead of failing — the smoke records absence,
 * it does not fake success.
 *
 * The LAN cleartext gate (doc 2.5) is exercised on the real path: the endpoint
 * `http://10.0.2.2:11434` is only contacted after [CleartextAuthorization.isPermitted]
 * passes for the exact host:port binding, and the same check without the
 * authorization is asserted to fail closed.
 */
@Suppress(
    "TooManyFunctions", // one class per smoke target (Ollama + sglang) sharing the fetch/parse helpers
)
@RunWith(AndroidJUnit4::class)
class SelfHostedSmokeTest {
    private var serverModel: String = ""
    private lateinit var provider: OpenAiChatProvider

    private val endpoint: NormalizedEndpoint
        get() = NormalizedEndpoint.parse("http://$HOST:$PORT/v1")

    @Before
    fun setUpGuarded() {
        val version = fetchText("http://$HOST:$PORT/api/version")
        assumeTrue(
            "no Ollama on the emulator host bridge $HOST:$PORT — smoke skipped " +
                "(start: ollama serve + ollama pull <model> on the dev machine)",
            version != null,
        )
        Log.d(TAG, "Ollama version: ${version?.trim()}")
        val models = fetchText("http://$HOST:$PORT/v1/models")
        assumeTrue("Ollama /v1/models returned nothing parseable — smoke skipped", models != null)
        val modelsBody = requireNotNull(models)
        val first = firstModelId(modelsBody)
        assumeTrue("Ollama has no pulled model — smoke skipped (ollama pull <model>)", first != null)
        serverModel = requireNotNull(first)
        Log.d(TAG, "smoke model: $serverModel (model list: $modelsBody)")

        // the app-layer LAN gate: exact host:port binding, fail closed otherwise
        val authorized = setOf(CleartextAuthorization(HOST, PORT))
        assertTrue(CleartextAuthorization.isPermitted(endpoint, authorized))
        assertTrue(!CleartextAuthorization.isPermitted(endpoint, emptySet()))
        assertEquals(CleartextAuthorization(HOST, PORT), CleartextAuthorization.requiredFor(endpoint))

        provider =
            OpenAiChatProvider(
                config =
                    ProviderConfig(
                        id = "ollama-smoke",
                        displayName = "Ollama Smoke",
                        protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        endpoint = endpoint,
                        model = serverModel,
                        headers = emptyMap(),
                        secretAlias = SecretAlias("ollama-local"),
                        capabilitySnapshot =
                            ProviderCapabilities.toJsonString(
                                ProviderCapabilities(
                                    streaming = true,
                                    toolCalls = true,
                                    parallelToolCalls = false,
                                    vision = false,
                                    reasoning = false,
                                    jsonSchemaOutput = false,
                                    maxContextTokens = null,
                                    source = CapabilitySource.MANUAL,
                                ),
                            ),
                    ),
                credentials = CredentialLookup { "ollama-local" },
                wire = OkHttpWireClient(),
                imageResolver =
                    ImageResolver {
                        throw UnsupportedOperationException("smoke is text-only; no images")
                    },
            )
    }

    @Test
    fun textStreamCompletesWithTextDelta() {
        val events =
            runBlocking {
                provider
                    .stream(
                        ModelRequest(
                            model = serverModel,
                            messages =
                                listOf(
                                    ModelMessage(ModelRole.USER, "Reply with the single word: ok"),
                                ),
                            maxOutputTokens = 32,
                        ),
                    ).toList()
            }
        Log.d(TAG, "text stream events: ${events.map { it::class.simpleName }}")
        val terminal = events.firstOrNull { isTerminal(it) }
        assertTrue("stream must end in a terminal, got ${events.last()}", terminal != null)
        assertTrue("text stream must end in Completed, got $terminal", terminal is ModelEvent.Completed)
        assertTrue("text stream must emit at least one TextDelta", events.any { it is ModelEvent.TextDelta })
        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        Log.d(TAG, "text stream evidence: model=$serverModel terminal=$terminal preview=${text.take(80)}")
    }

    @Test
    fun toolCallCompletesWithClosedToolIndex() {
        val events =
            runBlocking {
                provider
                    .stream(
                        ModelRequest(
                            model = serverModel,
                            messages =
                                listOf(
                                    ModelMessage(
                                        ModelRole.USER,
                                        "Call the echo tool with the text argument set to: probe",
                                    ),
                                ),
                            tools =
                                listOf(
                                    ModelToolSchema(
                                        ToolName("echo"),
                                        "Return the given text unchanged.",
                                        ECHO_TOOL_SCHEMA,
                                    ),
                                ),
                            maxOutputTokens = 256,
                        ),
                    ).toList()
            }
        Log.d(TAG, "tool stream events: ${events.map { it::class.simpleName }}")
        val terminal = events.firstOrNull { isTerminal(it) }
        assertTrue("tool stream must end in a terminal, got ${events.last()}", terminal != null)
        assertTrue("tool stream must end in Completed, got $terminal", terminal is ModelEvent.Completed)
        val completed = terminal as ModelEvent.Completed
        assertEquals("tool fixture must finish with tool_calls", "tool_calls", completed.finishReason)
        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>().map { it.index }.toSet()
        val finished = events.filterIsInstance<ModelEvent.ToolCallFinished>().map { it.index }.toSet()
        val closed = started.intersect(finished).firstOrNull()
        assertTrue(
            "a tool-call index must be both started and finished (started=$started finished=$finished)",
            closed != null,
        )
        val args =
            events
                .filterIsInstance<ModelEvent.ToolArgumentsDelta>()
                .filter { it.index == closed }
                .joinToString("") { it.jsonFragment }
        Log.d(TAG, "tool call evidence: model=$serverModel index=$closed args=${args.take(120)}")
    }

    @Test
    fun recordsServerCapabilitiesAndUnsupportedFields() {
        // list endpoint (phase 2): Ollama exposes the OpenAI-compatible /v1/models
        val listed = runBlocking { provider.listModels() }
        Log.d(TAG, "listModels: $listed")
        assertTrue(
            "Ollama must support /v1/models (recorded finding if changed)",
            listed is ModelCatalogResult.Listed,
        )
        val models = (listed as ModelCatalogResult.Listed).models
        assertTrue("the smoke model must be in the server list", models.contains(serverModel))

        // configuration check (phase 1): list-backed, one authenticated call
        val check = runBlocking { provider.validateConfiguration() }
        Log.d(TAG, "validateConfiguration: $check")
        assertTrue("configuration check must pass against a reachable Ollama", check is ProviderCheckResult.Ok)

        // recorded unsupported fields (doc 2.5): Ollama's OpenAI-compatible surface does
        // NOT implement the OpenAI Responses API (stateful conversation fields); the
        // provider protocol island therefore pins OPENAI_CHAT_COMPLETIONS for Ollama and
        // never falls back. Log for the completion record.
        Log.d(
            TAG,
            "unsupported fields recorded: Responses stateful fields not available on Ollama " +
                "OpenAI-compatible /v1 — protocol fixed to OPENAI_CHAT_COMPLETIONS, no fallback " +
                "(completion record HXA-027)",
        )
    }

    /** Terminal events for the smoke assertions (completed, refusal, error). */
    private fun isTerminal(event: ModelEvent): Boolean =
        event is ModelEvent.Completed || event is ModelEvent.Refusal || event is ModelEvent.Error

    /** Plain-HTTP GET (pre-check only); null when unreachable or non-2xx. */
    private fun fetchText(url: String): String? =
        try {
            val connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2_000
                    readTimeout = 5_000
                    requestMethod = "GET"
                }
            val code = connection.responseCode
            if (code !in 200..299) {
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: UnknownHostException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        } catch (e: ConnectException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        } catch (e: IOException) {
            // non-2xx is handled above; anything else at the stream level is a miss
            Log.d(TAG, "pre-check $url failed: ${e::class.simpleName}: ${e.message}")
            null
        }

    /** The first `id` string of an OpenAI-compatible models body (fail closed: null). */
    private fun firstModelId(modelsBody: String): String? {
        val from = modelsBody.indexOf("\"data\"")
        if (from < 0) return null
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(modelsBody.substring(from))?.groupValues?.get(1)
    }

    @Test
    fun sglangTextStreamCompletesWithTextDelta() {
        val events =
            runBlocking {
                sglangProvider()
                    .stream(
                        ModelRequest(
                            model = sglangModel(),
                            messages =
                                listOf(
                                    ModelMessage(ModelRole.USER, "用一句话介绍你自己。"),
                                ),
                            // The model reasons before answering (reasoning_content deltas);
                            // the budget must cover thinking + answer.
                            maxOutputTokens = 2000,
                        ),
                    ).toList()
            }
        Log.d(TAG, "sglang text stream events: ${events.map { it::class.simpleName }}")
        val terminal = events.firstOrNull { isTerminal(it) }
        assertTrue("stream must end in a terminal, got ${events.last()}", terminal != null)
        assertTrue("text stream must end in Completed, got $terminal", terminal is ModelEvent.Completed)
        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        assertTrue("sglang text stream must emit non-blank content, got '${text.take(40)}'", text.isNotBlank())
        Log.d(
            TAG,
            "sglang text evidence: model=${sglangModel()} terminal=$terminal " +
                "chars=${text.length} preview=${text.take(80)}",
        )
    }

    @Test
    fun sglangToolCallCompletesWithClosedToolIndex() {
        val events =
            runBlocking {
                sglangProvider()
                    .stream(
                        ModelRequest(
                            model = sglangModel(),
                            messages =
                                listOf(
                                    ModelMessage(
                                        ModelRole.USER,
                                        "使用 echo 工具发送文本 probe。必须调用工具，不要用纯文本回答。",
                                    ),
                                ),
                            tools =
                                listOf(
                                    ModelToolSchema(
                                        ToolName("echo"),
                                        "Return the given text unchanged.",
                                        ECHO_TOOL_SCHEMA,
                                    ),
                                ),
                            maxOutputTokens = 2000,
                        ),
                    ).toList()
            }
        Log.d(TAG, "sglang tool stream events: ${events.map { it::class.simpleName }}")
        val terminal = events.firstOrNull { isTerminal(it) }
        assertTrue("tool stream must end in a terminal, got ${events.last()}", terminal != null)
        assertTrue("tool stream must end in Completed, got $terminal", terminal is ModelEvent.Completed)
        val completed = terminal as ModelEvent.Completed
        assertEquals("tool fixture must finish with tool_calls", "tool_calls", completed.finishReason)
        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>().map { it.index }.toSet()
        val finished = events.filterIsInstance<ModelEvent.ToolCallFinished>().map { it.index }.toSet()
        val closed = started.intersect(finished).firstOrNull()
        assertTrue(
            "a tool-call index must be both started and finished (started=$started finished=$finished)",
            closed != null,
        )
        val args =
            events
                .filterIsInstance<ModelEvent.ToolArgumentsDelta>()
                .filter { it.index == closed }
                .joinToString("") { it.jsonFragment }
        assertTrue("tool args must mention the probe text, got: $args", args.contains("probe"))
        Log.d(TAG, "sglang tool evidence: model=${sglangModel()} index=$closed args=${args.take(120)}")
    }

    @Test
    fun sglangConfigurationCheckAndModelListPass() {
        val provider = sglangProvider()
        val listed = runBlocking { provider.listModels() }
        Log.d(TAG, "sglang listModels: $listed")
        assertTrue(
            "sglang must support /v1/models (recorded finding if changed)",
            listed is ModelCatalogResult.Listed,
        )
        val models = (listed as ModelCatalogResult.Listed).models
        assertTrue("the smoke model must be in the server list", models.contains(sglangModel()))
        val check = runBlocking { provider.validateConfiguration() }
        Log.d(TAG, "sglang validateConfiguration: $check")
        assertTrue(
            "configuration check must pass against a reachable sglang",
            check is ProviderCheckResult.Ok,
        )
        Log.d(
            TAG,
            "sglang protocol island recorded: OPENAI_CHAT_COMPLETIONS against the OpenAI-compatible " +
                "/v1 surface (streaming + tool calls verified); reasoning_content deltas are vendor " +
                "output, not a protocol event (completion record HXA-027 extension)",
        )
    }

    /** The dev-machine sglang server (host bridge 10.0.2.2, port 30008). */
    private val sglangEndpoint: NormalizedEndpoint
        get() = NormalizedEndpoint.parse("http://$HOST:$SGLANG_PORT/v1")

    private fun sglangModel(): String {
        val body = fetchText("http://$HOST:$SGLANG_PORT/v1/models")
        assumeTrue(
            "no sglang service on $HOST:$SGLANG_PORT — smoke skipped (start sglang on the dev machine)",
            body != null,
        )
        val model = firstModelId(requireNotNull(body))
        assumeTrue("sglang /v1/models returned nothing parseable — smoke skipped", model != null)
        return requireNotNull(model)
    }

    private fun sglangProvider(): OpenAiChatProvider =
        OpenAiChatProvider(
            config =
                ProviderConfig(
                    id = "sglang-smoke",
                    displayName = "sglang Smoke",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = sglangEndpoint,
                    model = sglangModel(),
                    headers = emptyMap(),
                    secretAlias = SecretAlias("sglang-local"),
                    capabilitySnapshot =
                        ProviderCapabilities.toJsonString(
                            ProviderCapabilities(
                                streaming = true,
                                toolCalls = true,
                                parallelToolCalls = false,
                                vision = false,
                                reasoning = true,
                                jsonSchemaOutput = false,
                                maxContextTokens = null,
                                source = CapabilitySource.MANUAL,
                            ),
                        ),
                ),
            credentials = CredentialLookup { "sglang-local" },
            wire = OkHttpWireClient(),
            imageResolver =
                ImageResolver {
                    throw UnsupportedOperationException("smoke is text-only; no images")
                },
        )

    public companion object {
        private const val TAG = "HelixSmoke"
        private const val HOST = "10.0.2.2"
        private const val PORT = 11434
        private const val SGLANG_PORT = 30008
        private const val ECHO_TOOL_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}," +
                "\"required\":[\"text\"]}"
    }
}
