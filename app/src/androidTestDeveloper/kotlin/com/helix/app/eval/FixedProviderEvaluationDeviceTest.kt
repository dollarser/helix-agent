package com.helix.app.eval

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderFactory
import com.helix.core.model.AssistantToolCall
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
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import com.helix.tools.framework.ToolDispatchOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The fixed corpus's nine chat/provider cases, using real adapters and the actual read dispatcher. */
class FixedProviderEvaluationDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val directory get() = File(app.filesDir, "hxa100")

    @Test
    fun fixedChatAndProviderCases() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("helix.eval") == "true")
            val config = Json.parseToJsonElement(File(directory, "config.json").readText()).jsonObject
            val corpus = File(directory, "fixed-evals.tsv").readBytes()
            require(sha256(corpus) == "f27bf8b51e61be248a6e642c22cefc3e5045d0d35e37518377eb9b8cdf85e795")
            val rows =
                corpus
                    .toString(Charsets.UTF_8)
                    .lines()
                    .filter { it.startsWith("chat-") || it.startsWith("provider-") }
            require(rows.size == 9)
            val failures = mutableListOf<String>()
            rows.forEach { line ->
                val cells = line.split('\t')
                val result = evaluate(cells, config)
                File(directory, "${cells[0]}.json").writeText(result.toString())
                if (result["result"]?.jsonPrimitive?.content != "PASS") failures += cells[0]
            }
            assertTrue("fixed evaluation failed: $failures", failures.isEmpty())
        }

    private suspend fun evaluate(
        cells: List<String>,
        config: JsonObject,
    ): JsonObject {
        val started = SystemClock.elapsedRealtime()
        val toolCase = cells[5] == "single_tool_then_complete"
        val provider = provider(ProviderProtocol.valueOf(cells[3]), config)
        val model = config.getValue("model").jsonPrimitive.content
        val messages =
            mutableListOf(
                ModelMessage(ModelRole.SYSTEM, EVAL_CONTEXT),
                ModelMessage(ModelRole.USER, cells[4]),
            )
        val descriptor = requireNotNull(container.toolPipeline.registry.resolveLatest(ToolName("time.now")))
        val tools =
            if (toolCase) {
                listOf(ModelToolSchema(descriptor.name, descriptor.description, descriptor.inputSchema.toString()))
            } else {
                emptyList()
            }
        val request = ModelRequest(model, messages, tools, temperature = 0.0, maxOutputTokens = 2048)
        val first = provider.stream(request).toList()
        val calls = first.filterIsInstance<ModelEvent.ToolCallStarted>()
        var events = first
        var dispatchVerified = false
        val singleRead = calls.singleOrNull()?.name == "time.now"
        if (toolCase && singleRead && terminal(first) is ModelEvent.Completed) {
            val call = calls.single()
            require(first.filterIsInstance<ModelEvent.ToolCallFinished>().any { it.index == call.index })
            val args =
                first
                    .filterIsInstance<ModelEvent.ToolArgumentsDelta>()
                    .filter { it.index == call.index }
                    .joinToString("") { it.jsonFragment }
            val id = "eval-${cells[0]}-${System.nanoTime()}"
            val now = System.currentTimeMillis()
            container.storage.sessions.create(id, "fixed eval", null, null, now)
            container.storage.turns.start(id, id, now)
            val outcome = container.chatService.dispatchToolCall("$id-call", id, call.name, args)
            if (outcome is ToolDispatchOutcome.Succeeded) {
                dispatchVerified = container.storage.toolResults.byToolCall("$id-call") != null
                val assistantCall = AssistantToolCall(call.id, ToolName(call.name), args)
                messages += ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(assistantCall))
                messages +=
                    ModelMessage(
                        ModelRole.TOOL,
                        outcome.result.payload.toString(),
                        toolCallId = call.id,
                        toolName = ToolName(call.name),
                    )
                val followUp = ModelRequest(model, messages, temperature = 0.0, maxOutputTokens = 2048)
                events = provider.stream(followUp).toList()
            }
        }
        return report(cells, config, events, first, dispatchVerified, SystemClock.elapsedRealtime() - started)
    }

    private fun report(
        cells: List<String>,
        config: JsonObject,
        events: List<ModelEvent>,
        first: List<ModelEvent>,
        dispatchVerified: Boolean,
        elapsedMillis: Long,
    ): JsonObject {
        val model = config.getValue("model").jsonPrimitive.content
        val toolCase = cells[5] == "single_tool_then_complete"
        val descriptor = requireNotNull(container.toolPipeline.registry.resolveLatest(ToolName("time.now")))
        val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
        val passed =
            (terminal(events) as? ModelEvent.Completed)?.finishReason == "stop" && text.isNotBlank() &&
                events.none { it is ModelEvent.ToolCallStarted || it is ModelEvent.Error } &&
                (!toolCase || dispatchVerified)
        return buildJsonObject {
            put("id", cells[0])
            put("protocol", cells[3])
            put("model", model)
            put("provider", config.getValue("provider"))
            put("providerReportedVersion", config.getValue("providerReportedVersion"))
            put("result", if (passed) "PASS" else "FAIL")
            put("verifier", cells[5])
            put("promptSha256", sha256(cells[4].toByteArray()))
            put("datasetSha256", sha256(File(directory, "fixed-evals.tsv").readBytes()))
            put("temperature", 0)
            put("systemContextSha256", sha256(EVAL_CONTEXT.toByteArray()))
            put("gitCommit", config.getValue("gitCommit"))
            put("dateUtc", config.getValue("dateUtc"))
            put(
                "device",
                buildJsonObject {
                    put("model", Build.MODEL)
                    put("api", Build.VERSION.SDK_INT)
                    put("abi", Build.SUPPORTED_ABIS.first())
                    put("pageSizeBytes", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                },
            )
            put("toolVersions", buildJsonObject { if (toolCase) put(descriptor.name.value, descriptor.version.value) })
            put("elapsedMillis", elapsedMillis)
            put("dispatchVerified", dispatchVerified)
            put("text", text)
            put("terminal", terminal(events).toString())
            put("eventTypes", JsonArray(first.map { JsonPrimitive(it.javaClass.simpleName) }))
        }
    }

    private fun provider(
        protocol: ProviderProtocol,
        settings: JsonObject,
    ): ModelProvider {
        val endpoint = NormalizedEndpoint.parse(settings.getValue("endpoint").jsonPrimitive.content)
        require(endpoint == NormalizedEndpoint.parse("http://10.0.2.2:${evaluationProviderPort()}/v1"))
        val config =
            ProviderConfig(
                "hxa100",
                "HXA-100",
                protocol,
                endpoint,
                settings.getValue("model").jsonPrimitive.content,
                emptyMap(),
                SecretAlias("no-key"),
                ProviderCapabilities.toJsonString(
                    ProviderCapabilities(true, true, false, false, false, false, null, CapabilitySource.MANUAL),
                ),
            )
        val factory =
            ProviderFactory(
                CredentialLookup { "helix-no-key" },
                ProviderFactory.defaultWire(),
                imageSource = { error("no image fixture") },
            )
        return factory.create(config)
    }

    private fun terminal(events: List<ModelEvent>): ModelEvent? =
        events.lastOrNull { it is ModelEvent.Completed || it is ModelEvent.Error || it is ModelEvent.Refusal }

    private companion object {
        const val EVAL_CONTEXT =
            "You are Helix, an Android assistant. Advanced enables separately granted " +
                "capabilities; it does not bypass scope, policy or exact approval for sensitive effects. " +
                "Use only offered tools. Keep final answers concise."
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
