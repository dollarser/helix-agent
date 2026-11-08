package com.helix.app.provider

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderTransport
import com.helix.core.model.ReasoningEffort
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in synthetic requests through the real bundled account provider, never private conversation data. */
class PromptCacheSequenceDeviceTest {
    @Suppress("LongMethod") // Keep bounded requests, partial evidence and finally publication in one lifetime.
    @Test
    fun repeatAndAppendOnlySequenceReportsServerUsage() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue("Real model acceptance not enabled", args.getString("promptCacheAcceptance") == "true")
            val application =
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            check(application.packageName == "com.helix.agent.developer.asrvalidation")
            val container = application.awaitAppContainer()
            check(application.account.signedIn.value)
            val provider = container.providerService.modelProviderFor(BuiltInModel.PROVIDER_ID)
            val evidence = File(application.cacheDir, "prompt-cache-sequence-result.json")
            val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()

            fun publish(status: String) {
                evidence.writeText(
                    buildJsonObject {
                        put("status", status)
                        put("providerId", BuiltInModel.PROVIDER_ID)
                        put("modelId", BuiltInModel.MODEL_ID)
                        put("protocol", (BuiltInModel.connection.transport as ProviderTransport.Network).protocol.name)
                        put("requestLimit", 4)
                        put("maxOutputTokensPerRequest", 256)
                        put("syntheticOnly", true)
                        put("cacheEvidence", "server-reported usage only; null means unavailable")
                        put("requests", JsonArray(rows))
                    }.toString(),
                )
            }
            val prefix =
                buildString {
                    append("Synthetic inventory for a cache accounting test. Never call tools. Reply with OK only.\n")
                    repeat(300) { index ->
                        append("Record $index: warehouse sector ${index % 17}; item synthetic-$index; ")
                        append("quantity ${index * 3 + 1}; condition stable; audit complete; no action required.\n")
                    }
                }
            val messages =
                mutableListOf(
                    ModelMessage(ModelRole.SYSTEM, prefix),
                    ModelMessage(ModelRole.USER, "Acknowledge this synthetic inventory. Reply with OK only."),
                )
            publish("started")
            var passed = false
            try {
                repeat(4) { index ->
                    val events =
                        withTimeout(180_000) {
                            provider
                                .stream(
                                    ModelRequest(
                                        BuiltInModel.MODEL_ID,
                                        messages.toList(),
                                        maxOutputTokens = 256,
                                        reasoning = ReasoningEffort.LOW,
                                    ),
                                ).toList()
                        }
                    val visible = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }.trim()
                    val usages = events.filterIsInstance<ModelEvent.Usage>()
                    val completed = events.any { it is ModelEvent.Completed }
                    val errored = events.any { it is ModelEvent.Error }
                    rows +=
                        buildJsonObject {
                            put("index", index)
                            put("shape", if (index < 2) "identical-request" else "append-only")
                            put("messageCount", messages.size)
                            put("inputCharacters", messages.sumOf { it.text.length })
                            put("completed", completed)
                            put("error", errored)
                            put("visibleReplyPresent", visible.isNotBlank())
                            put(
                                "usage",
                                JsonArray(
                                    usages.map { usage ->
                                        buildJsonObject {
                                            put("inputTokens", usage.inputTokens?.let(::JsonPrimitive) ?: JsonNull)
                                            put("outputTokens", usage.outputTokens?.let(::JsonPrimitive) ?: JsonNull)
                                            put(
                                                "cachedInputTokens",
                                                usage.cachedInputTokens?.let(::JsonPrimitive) ?: JsonNull,
                                            )
                                            put(
                                                "cacheCreationInputTokens",
                                                usage.cacheCreationInputTokens?.let(::JsonPrimitive) ?: JsonNull,
                                            )
                                        }
                                    },
                                ),
                            )
                        }
                    publish("running")
                    assertFalse("Bundled model returned an error", errored)
                    assertTrue("Bundled model did not complete", completed)
                    assertTrue("Bundled model returned no visible reply", visible.isNotBlank())
                    if (index in 1..2) {
                        messages += ModelMessage(ModelRole.ASSISTANT, visible)
                        messages +=
                            ModelMessage(
                                ModelRole.USER,
                                "Inventory unchanged. Acknowledge checkpoint $index with OK only.",
                            )
                    }
                }
                passed = true
                publish("passed")
            } finally {
                if (!passed) {
                    publish("failed_or_incomplete")
                }
            }
        }
}
