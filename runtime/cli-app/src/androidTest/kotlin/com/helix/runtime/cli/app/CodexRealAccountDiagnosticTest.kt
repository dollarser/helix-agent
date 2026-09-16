package com.helix.runtime.cli.app

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolName
import com.helix.provider.openai.responses.ResponsesStreamDecoder
import com.helix.runtime.cli.client.CliModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit synthetic prompts only. Never prints credentials, headers or response bodies. */
class CodexRealAccountDiagnosticTest {
    @Test fun systemInstructionCompatibility() {
        requireOptIn()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = CliSubscriptionCredentialVault(context)
        for (legacy in listOf(true, false)) {
            runCompatibilityCase(legacy, vault)
        }
    }

    /** One legacy/instructions wire-compatibility case. Split from the test to bound block depth. */
    private fun runCompatibilityCase(
        legacy: Boolean,
        vault: CliSubscriptionCredentialVault,
    ) {
        val client =
            OkHttpClient
                .Builder()
                .dns(BoundedDnsCache())
                .addInterceptor { chain ->
                    var request = chain.request()
                    if (legacy) {
                        val buffer = okio.Buffer()
                        request.body!!.writeTo(buffer)
                        val body = Json.parseToJsonElement(buffer.readUtf8()) as JsonObject
                        val inputs = body.getValue("input") as JsonArray
                        val system =
                            Json.parseToJsonElement(
                                """{"type":"message","role":"system","content":[
                    {"type":"input_text","text":"Reply exactly HELIX_OK"}]}""",
                            )
                        val old =
                            JsonObject(
                                body.minus("instructions") + ("input" to JsonArray(listOf(system) + inputs)),
                            )
                        val payload = old.toString().toRequestBody(CodexSubscriptionModel.JSON)
                        request = request.newBuilder().post(payload).build()
                    }
                    val response = chain.proceed(request)
                    val body = if (response.isSuccessful) "" else response.peekBody(8192).string()
                    val signals =
                        listOf("system", "instructions", "context", "token", "unsupported", "role")
                            .filter { body.contains(it, true) }
                    report("legacy=$legacy HTTP=${response.code} signals=$signals")
                    response
                }.build()
        OkHttpCodexOAuthTransport().use { transport ->
            CodexSubscriptionModel(vault, CodexLoginController(vault, transport), client).use { model ->
                val request =
                    plain().copy(
                        messages =
                            listOf(
                                ModelMessage(ModelRole.SYSTEM, "Reply exactly HELIX_OK"),
                                ModelMessage(ModelRole.USER, "Reply exactly HELIX_OK"),
                            ),
                    )
                val events = model.run(request).events
                if (!legacy) {
                    assertTrue("instructions request failed", events.lastOrNull() is ModelEvent.Completed)
                }
            }
        }
    }

    @Test fun highestDeclaredEffortContract() {
        withModel { model ->
            val events = model.run(plain().copy(reasoning = ReasoningEffort.fromWire("ultra"))).events
            report("ultra errors=${events.filterIsInstance<ModelEvent.Error>()}")
            assertTrue(events.lastOrNull() is ModelEvent.Completed)
        }
    }

    @get:Rule
    val activity = ActivityScenarioRule(CliRuntimeHomeActivity::class.java)

    @Test fun reasoningAndToolsContracts() {
        val cases =
            listOf(
                "low" to plain().copy(reasoning = ReasoningEffort.LOW),
                "high" to plain().copy(reasoning = ReasoningEffort.HIGH),
                "tools" to plain().copy(tools = listOf(tool())),
                "plainName" to plain().copy(tools = listOf(tool().copy(name = ToolName("fixture_echo")))),
            )
        val failures = mutableListOf<String>()
        withModel { model ->
            for ((name, request) in cases) {
                val events = model.run(request).events
                val errors = events.filterIsInstance<ModelEvent.Error>()
                report("case=$name errors=$errors terminal=${events.lastOrNull()?.javaClass?.simpleName}")
                if (errors.isNotEmpty() || events.lastOrNull() !is ModelEvent.Completed) failures += name
            }
        }
        assertTrue("failed cases: $failures", failures.isEmpty())
    }

    @Test fun realToolCallAndBackfillPreserveLocalIdentity() {
        val request =
            plain().copy(
                messages =
                    listOf(
                        ModelMessage(
                            ModelRole.USER,
                            "Call the provided echo tool once with text HELIX_OK, then reply exactly with its result.",
                        ),
                    ),
                tools = listOf(tool()),
            )
        withModel { model ->
            val events = model.run(request).events
            val start = events.filterIsInstance<ModelEvent.ToolCallStarted>().single()
            assertEquals(tool().name.value, start.name)
            val arguments =
                events
                    .filterIsInstance<ModelEvent.ToolArgumentsDelta>()
                    .filter { it.index == start.index }
                    .joinToString("") { it.jsonFragment }
            val call = AssistantToolCall(start.id, tool().name, arguments)
            val followup =
                request.copy(
                    messages =
                        request.messages +
                            listOf(
                                ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                                ModelMessage(ModelRole.TOOL, "HELIX_OK", toolCallId = start.id, toolName = tool().name),
                            ),
                )
            val result = model.run(followup).events
            assertTrue(result.lastOrNull() is ModelEvent.Completed)
            assertEquals(
                "HELIX_OK",
                result.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }.trim(),
            )
        }
    }

    @Test fun accountCatalogMetadata() {
        requireOptIn()
        val vault = CliSubscriptionCredentialVault(ApplicationProvider.getApplicationContext<Context>())
        OkHttpCodexOAuthTransport().use { transport ->
            val result = CodexModelCatalog(vault, CodexLoginController(vault, transport)).fetch()
            assertTrue("catalog failed: $result", result is CliModelCatalog.Listed)
            report("$result")
        }
    }

    @Test fun productionRequestContract() {
        withModel(inspectWire = false) { model ->
            val events = model.run(plain()).events
            assertTrue("production request failed", events.none { it is ModelEvent.Error })
            assertTrue(events.lastOrNull() is ModelEvent.Completed)
        }
    }

    private fun withModel(
        inspectWire: Boolean = true,
        block: (CodexSubscriptionModel) -> Unit,
    ) {
        requireOptIn()
        val vault = CliSubscriptionCredentialVault(ApplicationProvider.getApplicationContext<Context>())
        OkHttpCodexOAuthTransport().use { transport ->
            val client = if (inspectWire) diagnosticClient() else OkHttpClient.Builder().dns(BoundedDnsCache()).build()
            CodexSubscriptionModel(vault, CodexLoginController(vault, transport), client).use(block)
        }
    }

    private fun diagnosticClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val wire = response.peekBody(65536).string()
                val signals =
                    listOf(
                        "name",
                        "pattern",
                        "tools",
                        "strict",
                        "parameters",
                        "invalid",
                        "required",
                        "ultra",
                        "supported",
                        "unsupported",
                        "effort",
                        "reasoning",
                        "allowed",
                    ).filter { wire.contains(it, ignoreCase = true) }
                val kinds =
                    wire
                        .lineSequence()
                        .filter { it.startsWith("event:") }
                        .distinct()
                        .toList()
                val decoder = ResponsesStreamDecoder()
                decoder.feed(wire.toByteArray())
                decoder.finish()
                report(
                    "HTTP=${response.code} eventKinds=$kinds contractSignals=$signals " +
                        "decoderFailure=${decoder.failure}",
                )
                response
            }.build()

    private fun requireOptIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCodex") == "true")
    }

    private fun report(message: String) {
        val status = Bundle().apply { putString("stream", "$message\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)
    }

    private fun plain() = ModelRequest("gpt-6-astra", listOf(ModelMessage(ModelRole.USER, "Reply exactly HELIX_OK")))

    private fun tool() =
        ModelToolSchema(
            ToolName("fixture.echo"),
            "Echo the supplied text for a harmless test",
            """{"type":"object","properties":{"text":{"type":"string"}},
            "required":["text"],"additionalProperties":false}""",
        )
}
