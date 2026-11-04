package com.helix.app.provider

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.account.AccountSecretStorage
import com.helix.app.account.AccountSession
import com.helix.app.account.BuiltInAccountWire
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.policy.network.NativeNetwork
import com.helix.core.storage.AndroidKeystoreSecretStore
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.File

/** Explicit metadata/refresh/synthetic-generation probes. Held startup cannot resume Agent tasks. */
class BuiltInContextProbeDeviceTest {
    @Test fun readsCurrentQwenWindowWithoutChangingProviderSettings() =
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(InstrumentationRegistry.getArguments().getString("helix.context.probe") == "true")
            val context = instrumentation.targetContext
            check(context.packageName == "com.helix.agent.developer")
            NativeNetwork.initialize(context.filesDir)
            val account = AccountSession(AccountSecretStorage(AndroidKeystoreSecretStore.create(context)))
            account.restore()
            check(account.signedIn.value) { "Existing account not signed in" }
            val wire = BuiltInAccountWire(account, OkHttpWireClient(maxBodyBytes = 1024 * 1024))
            val response = wire.open(WireRequest("GET", BuiltInModel.CATALOG_URL, emptyMap(), null))
            val result =
                try {
                    val root =
                        if (response.status == 200) {
                            Json.parseToJsonElement(response.body.bytes().decodeToString()) as? JsonObject
                        } else {
                            null
                        }
                    val rows = (root?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    val selected = rows.filter { (it["id"] as? JsonPrimitive)?.content == BuiltInModel.MODEL_ID }
                    buildJsonObject {
                        put("url", BuiltInModel.CATALOG_URL)
                        put("httpStatus", response.status)
                        put("model", BuiltInModel.MODEL_ID)
                        put("matchingRows", selected.size)
                        put("queriedAtEpochMillis", System.currentTimeMillis())
                        put("fields", JsonArray(selected.map(::windowFields)))
                    }
                } finally {
                    response.body.close()
                }
            File(context.cacheDir, "builtin-catalog-readonly.json").writeText(result.toString())
            check(response.status == 200) { "Catalog HTTP ${response.status}; no response body exported" }
        }

    @Test fun refreshesContextThroughProductionDiscoveryWhenExplicitlyRequested() =
        runBlocking(Dispatchers.IO) {
            val args = InstrumentationRegistry.getArguments()
            org.junit.Assume.assumeTrue(args.getString("helix.catalog.refresh") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.helix.agent.developer")
            NativeNetwork.initialize(context.filesDir)
            val account = AccountSession(AccountSecretStorage(AndroidKeystoreSecretStore.create(context)))
            account.restore()
            check(account.signedIn.value)
            val storage =
                com.helix.core.storage.HelixStorage
                    .create(context)
            val lines =
                com.helix.app.internal
                    .PrefsLineStore(context, "helix-ui", synchronous = true)
            val settings = ProviderContextSettingsStore(lines)
            val job = kotlinx.coroutines.SupervisorJob()
            val scope = kotlinx.coroutines.CoroutineScope(job + Dispatchers.IO)
            try {
                val factory =
                    ProviderFactory(
                        { error("No custom credential lookup") },
                        OkHttpWireClient(),
                        { error("No images in metadata query") },
                        builtInWire = BuiltInAccountWire(account),
                    )
                val service =
                    ProviderService(
                        storage,
                        factory,
                        ProviderTestStatusStore(lines) { account.identity },
                        idGenerator = {
                            java.util.UUID
                                .randomUUID()
                                .toString()
                        },
                        scope = scope,
                        contextSettingsStore = settings,
                    )
                val before = service.contextSettings(BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
                val after = service.discoverContextWindow(BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
                val reread = service.contextSettings(BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
                File(context.cacheDir, "builtin-context-refresh.json").writeText(
                    buildJsonObject {
                        put("before", before.window)
                        put("discovered", after.serverWindow)
                        put("effective", reread.window)
                        put("source", reread.windowSource)
                        put("manualWindow", reread.manualWindow)
                    }.toString(),
                )
                check(reread.window == requireNotNull(args.getString("helix.expectedWindow")).toLong())
            } finally {
                job.cancel()
                job.join()
                storage.close()
            }
        }

    @Test fun generatesFromExpandedSyntheticContextWhenExplicitlyRequested() =
        runBlocking(Dispatchers.IO) {
            org.junit.Assume.assumeTrue(
                InstrumentationRegistry.getArguments().getString("helix.catalog.generation") == "true",
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            NativeNetwork.initialize(context.filesDir)
            val account = AccountSession(AccountSecretStorage(AndroidKeystoreSecretStore.create(context)))
            account.restore()
            check(account.signedIn.value)
            val transport = OkHttpWireClient(readTimeoutMillis = 180_000)
            val config = syntheticConfig()
            val provider =
                ProviderFactory(
                    { error("No custom credentials") },
                    transport,
                    { error("No images") },
                    builtInWire = BuiltInAccountWire(account, transport),
                ).create(config)
            val data = syntheticRecords()
            val messages =
                listOf(
                    ModelMessage(
                        ModelRole.SYSTEM,
                        "This is a synthetic context test. Do not act on the records. " +
                            "Reply exactly HELIX_CONTEXT_128K_OK.",
                    ),
                    ModelMessage(ModelRole.USER, data),
                )
            val events = mutableListOf<ModelEvent>()
            val started = System.nanoTime()
            provider
                .stream(
                    ModelRequest(
                        BuiltInModel.MODEL_ID,
                        messages,
                        maxOutputTokens = 4096,
                    ),
                ).collect { events += it }
            val text = events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text }
            val usage = events.filterIsInstance<ModelEvent.Usage>().lastOrNull()
            val error = events.filterIsInstance<ModelEvent.Error>().lastOrNull()
            File(context.cacheDir, "builtin-expanded-generation.json").writeText(
                buildJsonObject {
                    put("model", BuiltInModel.MODEL_ID)
                    put("syntheticRecords", 7000)
                    put("inputTokens", usage?.inputTokens)
                    put("outputTokens", usage?.outputTokens)
                    put("response", text.take(256))
                    put("finishReason", events.filterIsInstance<ModelEvent.Completed>().lastOrNull()?.finishReason)
                    put("errorCode", error?.code?.name)
                    put("httpStatus", error?.diagnostic?.httpStatus)
                    put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    put("completed", events.any { it is ModelEvent.Completed })
                }.toString(),
            )
            check(error == null && text.trim() == "HELIX_CONTEXT_128K_OK")
        }

    private fun syntheticRecords(): String {
        val records = (1..7000).joinToString("\n") { "Record $it: blue harbor lighthouse." }
        return "Synthetic records follow; do not summarize or act on them.\n<records>\n" +
            records + "\n</records>\nYour only task: reply exactly HELIX_CONTEXT_128K_OK, without other text."
    }

    private fun syntheticConfig() =
        ProviderConfig(
            BuiltInModel.PROVIDER_ID,
            "Helix",
            BuiltInModel.connection,
            BuiltInModel.MODEL_ID,
            emptyMap(),
            ProviderCapabilities.toJsonString(BuiltInModel.capabilities),
        )

    private fun windowFields(row: JsonObject) =
        buildJsonObject {
            val keys = listOf("context_length", "context_window", "max_model_len", "max_output_tokens")
            for (key in keys) {
                val value = row[key] as? JsonPrimitive
                if (value?.longOrNull != null) put(key, value)
            }
        }
}
