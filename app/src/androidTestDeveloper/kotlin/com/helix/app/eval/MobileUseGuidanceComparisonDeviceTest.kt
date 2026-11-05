package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.provider.api.ModelProvider
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/** Guidance-only A/B with a real configured model. No calls execute and no phone-task success is inferred. */
class MobileUseGuidanceComparisonDeviceTest {
    @Test fun scoringAcceptsEquivalentObservedSelectorsAndRejectsMissingPlans() {
        for (selector in listOf("text" to "下载", "viewId" to "store:id/download")) {
            val call =
                buildJsonObject {
                    put("name", JsonPrimitive("ui.click_match"))
                    put("args", buildJsonObject { put(selector.first, JsonPrimitive(selector.second)) })
                }
            assertTrue(preferred("known-selector", call))
        }
        assertFalse(preferred("known-selector", null))
        val invalid =
            buildJsonObject {
                put("name", JsonPrimitive("ui.click_match"))
                put("args", buildJsonObject { put("text", JsonObject(emptyMap())) })
            }
        assertFalse(preferred("known-selector", invalid))
    }

    @Test
    fun compareFirstDecisions() =
        runBlocking<Unit>(Dispatchers.IO) {
            val enabled = InstrumentationRegistry.getArguments().getString("helixMobileGuidanceComparison")
            assumeTrue("Explicit configured-model comparison opt-in required", enabled != null)
            require(enabled == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            app.runtimeRecoveryReady.await()
            val baseline =
                InstrumentationRegistry
                    .getInstrumentation()
                    .context.assets
                    .open("mobile-use/guidance-baseline.json")
                    .bufferedReader()
                    .use {
                        Json.parseToJsonElement(it.readText()).jsonObject
                    }
            val currentGuidance =
                app.assets
                    .open("plugins/mobile-use/required.md")
                    .bufferedReader()
                    .use { it.readText() }
            val descriptors =
                app.appContainer.toolPipeline.registry
                    .snapshot()
                    .map { it.descriptor }
                    .filter { it.name.value.startsWith("ui.") }
                    .associateBy { it.name.value }
            val provider =
                app.appContainer.providerService.modelProviderFor(
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val records = mutableListOf<JsonObject>()
            var complete = false
            try {
                // Counterbalance order, keeping the same scenarios/model/runtime and schema constraints.
                repeat(2) { round ->
                    for (scenario in scenarios()) {
                        val order = if (round == 0) listOf("baseline", "current") else listOf("current", "baseline")
                        for (variant in order) {
                            val schemas = schemas(descriptors.values, baseline, variant)
                            val guidance =
                                if (variant == "baseline") {
                                    baseline.getValue("requiredGuidance").jsonPrimitive.content
                                } else {
                                    currentGuidance
                                }
                            records += observe(provider, descriptors, schemas, guidance, scenario, variant, round)
                        }
                    }
                }
                assertEquals(16, records.size)
                complete = true
            } finally {
                saveResult(app, baseline, records, complete)
            }
        }

    private fun saveResult(
        app: HelixApplication,
        baseline: JsonObject,
        records: List<JsonObject>,
        complete: Boolean,
    ) {
        val result =
            buildJsonObject {
                put("model", JsonPrimitive(BuiltInModel.MODEL_ID))
                put("measurementComplete", JsonPrimitive(complete))
                put("baselineProvenance", baseline.getValue("provenance"))
                put("taskCompletionMeasured", JsonPrimitive(false))
                put("executedToolCalls", JsonPrimitive(0))
                put("records", JsonArray(records))
            }
        val directory = java.io.File(app.filesDir, "validation/mobile-guidance-comparison").apply { mkdirs() }
        directory.resolve("result.json").writeText(result.toString())
    }

    private fun schemas(
        descriptors: Collection<ToolDescriptor>,
        baseline: JsonObject,
        variant: String,
    ): List<ModelToolSchema> =
        descriptors.map { descriptor ->
            val name = descriptor.name.value
            val old =
                baseline
                    .getValue("descriptions")
                    .jsonObject[name]
                    ?.jsonPrimitive
                    ?.content
            var input = descriptor.inputSchema
            if (variant == "baseline" && name in setOf("ui.wait", "ui.click_match")) {
                val properties = input.getValue("properties").jsonObject
                val poll = properties.getValue("pollMillis").jsonObject
                input =
                    JsonObject(
                        input + (
                            "properties" to
                                JsonObject(
                                    properties +
                                        ("pollMillis" to JsonObject(poll - "default" - "description")),
                                )
                        ),
                    )
            }
            ModelToolSchema(
                descriptor.name,
                if (variant == "baseline") old ?: descriptor.description else descriptor.description,
                input.toString(),
            )
        }

    @Suppress("LongParameterList")
    private suspend fun observe(
        provider: ModelProvider,
        descriptors: Map<String, ToolDescriptor>,
        schemas: List<ModelToolSchema>,
        guidance: String,
        scenario: Scenario,
        variant: String,
        round: Int,
    ): JsonObject {
        val request =
            ModelRequest(
                BuiltInModel.MODEL_ID,
                listOf(
                    ModelMessage(ModelRole.SYSTEM, guidance),
                    ModelMessage(ModelRole.USER, scenario.prompt),
                ),
                tools = schemas,
                maxOutputTokens = 1536,
            )
        val start = android.os.SystemClock.elapsedRealtime()
        val events = withTimeout(90_000) { provider.stream(request).toList() }
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        requireCompleted(events)
        val calls = calls(events)
        val invalidSchemas =
            calls.count { call ->
                val descriptor = descriptors[call.text("name")]
                call["invalidJson"] != null || descriptor == null ||
                    ToolSchemaValidator.validate(
                        descriptor.inputSchema,
                        call.getValue("args"),
                    ) !is ToolSchemaValidation.Valid
            }
        val tokenReferences =
            calls.count {
                val tool = it.text("name")
                val token = it.getValue("args").jsonObject.text("token")
                scenario.name == "latest-token" && tool == "ui.click" && token != LATEST_TOKEN
            }
        val observations =
            calls.count {
                val tool = it.text("name")
                scenario.name == "known-selector" && tool in setOf("ui.find", "ui.snapshot")
            }
        val coordinates =
            calls.count {
                scenario.name == "transition" && it.text("name") == "ui.gesture"
            }
        return buildJsonObject {
            put("variant", JsonPrimitive(variant))
            put("round", JsonPrimitive(round))
            put("scenario", JsonPrimitive(scenario.name))
            put("prompt", JsonPrimitive(scenario.prompt))
            put("surfaceSha256", JsonPrimitive(sha256(guidance + schemas.toString())))
            put("firstDecisionLatencyMs", JsonPrimitive(elapsed))
            put("calls", JsonArray(calls))
            put(
                "responseText",
                JsonPrimitive(events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }),
            )
            put("invalidSchemaCalls", JsonPrimitive(invalidSchemas))
            put("invalidTokenReferences", JsonPrimitive(tokenReferences))
            put("extraObservations", JsonPrimitive(observations))
            put("coordinatePlansWithoutFreshPixels", JsonPrimitive(coordinates))
            put("preferredFirstDecision", JsonPrimitive(preferred(scenario.name, calls.firstOrNull())))
        }
    }

    private fun requireCompleted(events: List<ModelEvent>) {
        check(events.none { it is ModelEvent.Error } && events.any { it is ModelEvent.Completed }) {
            "MODEL_COMPARISON_RESPONSE_FAILED"
        }
    }

    private fun calls(events: List<ModelEvent>): List<JsonObject> {
        val args = events.filterIsInstance<ModelEvent.ToolArgumentsDelta>().groupBy { it.index }
        return events.filterIsInstance<ModelEvent.ToolCallStarted>().map { call ->
            val raw = args[call.index].orEmpty().joinToString("") { it.jsonFragment }
            val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            buildJsonObject {
                put("name", JsonPrimitive(call.name))
                put("args", parsed ?: JsonObject(emptyMap()))
                if (parsed == null) put("invalidJson", JsonPrimitive(true))
            }
        }
    }

    private fun preferred(
        scenario: String,
        call: JsonObject?,
    ): Boolean {
        val name = call?.get("name")?.jsonPrimitive?.content ?: return false
        val args = call.getValue("args").jsonObject
        return when (scenario) {
            "known-selector" -> {
                name == "ui.click_match" && (
                    args.text("text") == "下载" ||
                        args.text("viewId") == "store:id/download"
                )
            }

            "latest-token" -> {
                (name == "ui.click" && args.text("token") == LATEST_TOKEN) ||
                    (name == "ui.click_match" && args.text("text") == "下载")
            }

            "wait" -> {
                name == "ui.wait"
            }

            else -> {
                name in setOf("ui.snapshot", "ui.device", "ui.wait")
            }
        }
    }

    private fun scenarios() =
        listOf(
            Scenario(
                "known-selector",
                "任务：点击商店详情页的下载。已观察到包com.example.store、viewId=store:id/download、" +
                    "唯一按钮text=下载且可点击，但没有可用令牌。只选择下一步工具，不要汇报完成。",
            ),
            Scenario(
                "latest-token",
                "任务：点击下载。先前ui.snapshot的下载token=$OLD_TOKEN；随后一次ui.find又观察到" +
                    "同一唯一下载按钮，最新suggestedClickToken=$LATEST_TOKEN，包com.example.store。只选择下一步。",
            ),
            Scenario("wait", "下载仍在进行，请只等待text=打开的按钮出现，最多60秒，现在不要点击它。选择下一步工具。"),
            Scenario(
                "transition",
                "启动广告截图后的ui.gesture返回TARGET_CHANGED、sideEffectFree=true。" +
                    "旧图中有跳过按钮，但转场可能已结束；目前没有新截图。任务是进入应用主界面，请选择下一步。",
            ),
        )

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content

    private data class Scenario(
        val name: String,
        val prompt: String,
    )

    private companion object {
        val OLD_TOKEN = "p" + "a".repeat(31)
        val LATEST_TOKEN = "p" + "b".repeat(31)
    }
}

private fun sha256(text: String) =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
