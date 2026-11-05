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

/** Real configured model, synthetic observations. Model-produced calls are recorded, never executed. */
class MobileUseToolPlanningDeviceTest {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    fun configuredModelPlansMobileTools() =
        runBlocking<Unit>(Dispatchers.IO) {
            val enabled = InstrumentationRegistry.getArguments().getString("helixMobileToolPlanning")
            assumeTrue("Explicit real-model planning opt-in required", enabled != null)
            require(enabled == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            app.runtimeRecoveryReady.await()
            val c = app.appContainer
            c.providerService.builtInModels.refresh(force = false)
            val descriptors =
                c.toolPipeline.registry
                    .snapshot()
                    .map { it.descriptor }
                    .filter { it.name.value.startsWith("ui.") }
            assertTrue(
                descriptors
                    .single { it.name.value == "ui.apps" }
                    .inputSchema
                    .toString()
                    .contains("query"),
            )
            val schemas = descriptors.map { ModelToolSchema(it.name, it.description, it.inputSchema.toString()) }
            val provider = c.providerService.modelProviderFor(BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
            val guidance =
                app.assets
                    .open("plugins/mobile-use/required.md")
                    .bufferedReader()
                    .use { it.readText() }
            val records = mutableListOf<JsonObject>()
            val failures = mutableListOf<String>()
            val token = "p" + "a".repeat(31)
            val cases =
                listOf(
                    "scroll" to "请直接滚动标签栏，以查看右侧更多标签。最新观察：" +
                        "token=$token, className=android.widget.HorizontalScrollView, scrollable=true, " +
                        "scrollDirections=[forward,right]。没有截图。选择下一步工具。",
                    "app-query" to "核实X浏览器安装状态。包名未知。",
                    "background-completion" to "安装器及包查询均确认安装完成。" +
                        "当前 executionDisplay=background，originatingHelixPackage=com.helix.agent.developer，" +
                        "Helix在主屏可见。完成交接并汇报。",
                )
            for ((name, prompt) in cases) {
                val events =
                    withTimeout(90_000) {
                        provider
                            .stream(
                                ModelRequest(
                                    BuiltInModel.MODEL_ID,
                                    listOf(
                                        ModelMessage(ModelRole.SYSTEM, guidance),
                                        ModelMessage(ModelRole.USER, prompt),
                                    ),
                                    tools = schemas,
                                    maxOutputTokens = 1024,
                                ),
                            ).toList()
                    }
                val names = events.filterIsInstance<ModelEvent.ToolCallStarted>().associate { it.index to it.name }
                val args =
                    events
                        .filterIsInstance<ModelEvent.ToolArgumentsDelta>()
                        .groupBy { it.index }
                        .mapValues { (_, parts) ->
                            Json.parseToJsonElement(parts.joinToString("") { it.jsonFragment }).jsonObject
                        }
                val calls =
                    names.map { (index, tool) ->
                        buildJsonObject {
                            put("name", JsonPrimitive(tool))
                            put("args", args[index] ?: JsonObject(emptyMap()))
                        }
                    }
                val errors = events.filterIsInstance<ModelEvent.Error>().map { it.code.name }
                records +=
                    buildJsonObject {
                        put("case", JsonPrimitive(name))
                        put("calls", JsonArray(calls))
                        put("errors", JsonArray(errors.map(::JsonPrimitive)))
                    }
                if (errors.isNotEmpty() || events.none { it is ModelEvent.Completed }) failures += "$name:model"
                val first = names.entries.firstOrNull()
                val firstArgs = first?.key?.let(args::get)
                val tokenValue = firstArgs?.get("token")?.jsonPrimitive?.content
                val direction = firstArgs?.get("direction")?.jsonPrimitive?.content
                val valid =
                    when (name) {
                        "scroll" -> {
                            first?.value == "ui.scroll" && tokenValue == token &&
                                direction in setOf("forward", "right")
                        }

                        "app-query" -> {
                            val query = firstArgs?.get("query")?.jsonPrimitive?.content
                            first?.value == "ui.apps" && query?.contains("浏览器") == true &&
                                firstArgs?.containsKey("packageName") != true
                        }

                        else -> {
                            names.none { (index, tool) ->
                                val target = args[index]?.get("packageName")?.jsonPrimitive?.content
                                tool == "ui.launch" && target == "com.helix.agent.developer"
                            }
                        }
                    }
                if (!valid) failures += name
            }
            val directory = java.io.File(app.filesDir, "validation/mobile-tool-planning").apply { mkdirs() }
            directory.resolve("result.json").writeText(
                buildJsonObject {
                    put("model", JsonPrimitive(BuiltInModel.MODEL_ID))
                    put("cases", JsonArray(records))
                    put("failures", JsonArray(failures.map(::JsonPrimitive)))
                    put("executedToolCalls", JsonPrimitive(0))
                }.toString(),
            )
            assertEquals(3, records.size)
            assertFalse("Model planning failures: $failures; inspect scoped result.json", failures.isNotEmpty())
        }
}
