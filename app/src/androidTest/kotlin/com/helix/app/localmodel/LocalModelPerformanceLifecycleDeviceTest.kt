package com.helix.app.localmodel

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.local.LocalCancelResult
import com.helix.provider.api.local.LocalGenerationRequest
import com.helix.provider.api.local.LocalModelLoadRequest
import com.helix.provider.api.local.LocalUnloadResult
import com.helix.provider.api.local.ModelAssetRef
import com.helix.provider.api.local.ModelAssetStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** P5-only measurement of lifecycle latencies. No runtime tuning or production instrumentation. */
class LocalModelPerformanceLifecycleDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    @Test
    @Suppress("LongMethod")
    fun recordsCancelUnloadReloadAndTerminateLatencies() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("realModel") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val asset =
                ModelAssetRef(
                    requireNotNull(args.getString("modelSha")),
                    requireNotNull(args.getString("modelSha")),
                    requireNotNull(args.getString("modelSize")).toLong(),
                )
            val contextTokens = args.getString("contextTokens", "4096").toInt()
            require(contextTokens in 512..32768)
            val evidence = File(context.filesDir, "hxa222-evidence").apply { mkdirs() }
            val phases = File(evidence, "baseline-phases.tsv")

            fun phase(
                name: String,
                state: String,
            ) {
                phases.appendText("${SystemClock.elapsedRealtime()}\t$name\t$state\n")
            }
            val client = LocalInferenceRuntimeClient(context, ModelAssetStore(File(context.filesDir, "models")))
            val loading = LocalModelLoadRequest(asset, contextTokens, THREADS)
            try {
                phase("lifecycle-reload", "start")
                val reloadStart = SystemClock.elapsedRealtime()
                val loaded = client.load(loading)
                val reloadMs = SystemClock.elapsedRealtime() - reloadStart
                phase("lifecycle-reload", "end")

                val request =
                    ModelRequest(
                        asset.id,
                        listOf(
                            ModelMessage(
                                ModelRole.USER,
                                "Write the integers from 1 to 10000, one per line. /no_think",
                            ),
                        ),
                        maxOutputTokens = 2048,
                        reasoning = ReasoningEffort.OFF,
                    )
                val generation =
                    launch {
                        client.generate(LocalGenerationRequest("p5-cancel", loaded.handle, request)).toList()
                    }
                withTimeout(10_000) { while (client.runtimeStatus().activeGenerationId == null) delay(20) }
                delay(250)
                phase("lifecycle-cancel", "start")
                val cancelStart = SystemClock.elapsedRealtime()
                generation.cancelAndJoin()
                assertEquals(LocalCancelResult.EXITED, client.cancel("p5-cancel"))
                val cancelToExitMs = SystemClock.elapsedRealtime() - cancelStart
                phase("lifecycle-cancel", "end")
                assertNull(client.runtimeStatus().activeGenerationId)
                assertTrue("cancel-to-exit exceeded 10 seconds: $cancelToExitMs", cancelToExitMs < 10_000)

                phase("lifecycle-unload", "start")
                val unloadStart = SystemClock.elapsedRealtime()
                assertEquals(LocalUnloadResult.UNLOADED, client.unload(loaded.handle))
                val unloadMs = SystemClock.elapsedRealtime() - unloadStart
                phase("lifecycle-unload", "end")
                assertNull(client.runtimeStatus().loaded)

                phase("lifecycle-second-load", "start")
                val secondLoadStart = SystemClock.elapsedRealtime()
                client.load(loading)
                val secondLoadMs = SystemClock.elapsedRealtime() - secondLoadStart
                phase("lifecycle-second-load", "end")

                phase("lifecycle-terminate", "start")
                val terminateStart = SystemClock.elapsedRealtime()
                client.terminate()
                val terminateMs = SystemClock.elapsedRealtime() - terminateStart
                phase("lifecycle-terminate", "end")
                assertNull(client.runtimeStatus().loaded)
                assertTrue("terminate exceeded 7 seconds: $terminateMs", terminateMs < 7_000)

                File(evidence, "p5-lifecycle.json").writeText(
                    buildJsonObject {
                        put("contextTokens", contextTokens)
                        put("threads", THREADS)
                        put("postTaskReloadMs", reloadMs)
                        put("cancelToExitMs", cancelToExitMs)
                        put("unloadMs", unloadMs)
                        put("secondLoadMs", secondLoadMs)
                        put("terminateMs", terminateMs)
                    }.toString(),
                )
            } finally {
                runCatching { client.terminate() }
            }
        }

    private companion object {
        const val THREADS = 2
    }
}
