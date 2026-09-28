package com.helix.app.localmodel

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.local.LocalCancelResult
import com.helix.provider.api.local.LocalGenerationRequest
import com.helix.provider.api.local.LocalModelLoadRequest
import com.helix.provider.api.local.LocalRuntimeException
import com.helix.provider.api.local.LocalUnloadResult
import com.helix.provider.api.local.ModelAssetRef
import com.helix.provider.api.local.ModelAssetStore
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in native lifecycle checks on the owned 4 GiB API36 emulator and pinned Qwen fixture. */
class LocalModelLifecycleDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    @Test
    @Suppress("LongMethod") // One owned native lifecycle, including failure recovery and final cleanup.
    fun oversizedContextCancellationUnloadAndRestart() {
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("realModel") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val hash = args.getString("modelSha")!!
            val asset = ModelAssetRef(hash, hash, args.getString("modelSize")!!.toLong())
            val client = LocalInferenceRuntimeClient(context, ModelAssetStore(File(context.filesDir, "models")))
            val evidence = File(context.filesDir, "hxa222-evidence").apply { mkdirs() }
            val record = File(evidence, "lifecycle.txt").apply { writeText("") }
            try {
                val oversized = runCatching { client.load(LocalModelLoadRequest(asset, 32768, 2)) }.exceptionOrNull()
                assertTrue(
                    "32K must fail on this 4 GiB fixture: $oversized",
                    oversized is LocalRuntimeException,
                )
                assertEquals(ModelErrorCode.LOCAL_RUNTIME_OOM, (oversized as LocalRuntimeException).code)
                assertNull(client.runtimeStatus().loaded)
                record.appendText("32K preflight: LOCAL_RUNTIME_OOM\n")
                val loaded = client.load(LocalModelLoadRequest(asset, 4096, 2))
                assertEquals(loaded.handle, client.load(loaded.request).handle)
                delay(12000) // Sample loaded residency before comparing with the unloaded process.
                val request =
                    ModelRequest(
                        asset.id,
                        listOf(
                            ModelMessage(ModelRole.USER, "Write the integers from 1 to 10000, one per line. /no_think"),
                        ),
                        maxOutputTokens = 2048,
                        reasoning = ReasoningEffort.OFF,
                    )
                val generation =
                    launch {
                        client.generate(LocalGenerationRequest("cancel-check", loaded.handle, request)).toList()
                    }
                withTimeout(10000) { while (client.runtimeStatus().activeGenerationId == null) delay(20) }
                delay(1000)
                assertEquals(LocalUnloadResult.BUSY, client.unload(loaded.handle))
                val start = SystemClock.elapsedRealtime()
                generation.cancelAndJoin()
                assertEquals(LocalCancelResult.EXITED, client.cancel("cancel-check"))
                val elapsed = SystemClock.elapsedRealtime() - start
                assertTrue("Cancel executor exit exceeded 10 seconds: $elapsed", elapsed < 10000)
                assertNull(client.runtimeStatus().activeGenerationId)
                record.appendText("cancel-to-exit-ms=$elapsed\n")
                val limitedRequest =
                    LocalGenerationRequest(
                        "limit-check",
                        loaded.handle,
                        request.copy(maxOutputTokens = 1),
                    )
                val limited = client.generate(limitedRequest).toList()
                assertTrue(limited.any { it is ModelEvent.Usage && it.outputTokens == 1L })
                assertEquals(ModelEvent.Error(ModelErrorCode.LOCAL_OUTPUT_LIMIT, false), limited.last())
                assertTrue(limited.none { it is ModelEvent.ToolCallStarted || it is ModelEvent.Completed })
                record.appendText("output-limit: usage + LOCAL_OUTPUT_LIMIT, no completed/tool call\n")
                assertEquals(LocalUnloadResult.UNLOADED, client.unload(loaded.handle))
                assertNull(client.runtimeStatus().loaded)
                record.appendText("unloaded=true\n")
                delay(12000) // Host memory sampler observes the idle, unloaded native process.
                val beforeCrash = client.load(loaded.request)
                val crashed =
                    async {
                        runCatching {
                            client.generate(LocalGenerationRequest("death-check", beforeCrash.handle, request)).toList()
                        }.exceptionOrNull()
                    }
                withTimeout(10000) { while (client.runtimeStatus().activeGenerationId == null) delay(20) }
                delay(1000)
                client.terminate()
                val deathFailure = withTimeout(10000) { crashed.await() }
                assertTrue(
                    "Native process death must fail the current request: $deathFailure",
                    deathFailure is LocalRuntimeException,
                )
                assertEquals(ModelErrorCode.LOCAL_RUNTIME_CRASHED, (deathFailure as LocalRuntimeException).code)
                assertNull(client.runtimeStatus().loaded)
                record.appendText("active-generation-death=LOCAL_RUNTIME_CRASHED\n")
                val restarted = client.load(loaded.request)
                assertNotEquals(loaded.handle, restarted.handle)
                val response =
                    request.copy(
                        messages = listOf(ModelMessage(ModelRole.USER, "Reply only OK. /no_think")),
                        maxOutputTokens = 32,
                    )
                val restartRequest = LocalGenerationRequest("restart-check", restarted.handle, response)
                val events = client.generate(restartRequest).toList()
                assertTrue(events.last() is ModelEvent.Completed)
                assertTrue(events.any { it is ModelEvent.TextDelta })
                record.appendText("restart-new-handle=true; real generation completed\n")
            } finally {
                client.terminate()
            }
        }
    }
}
