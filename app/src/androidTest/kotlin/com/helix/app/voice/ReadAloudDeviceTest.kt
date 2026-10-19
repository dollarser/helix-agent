package com.helix.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.GeneratedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real speech is synthesized to files only. Control tests use silent injected sinks, never the microphone. */
class ReadAloudDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun await(check: () -> Boolean) =
        runBlocking {
            withTimeout(15_000) { while (!check()) delay(10) }
        }

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Test fun realMeloChineseAndMixedTextProducesFiniteNonSilentPcm(): Unit =
        runBlocking {
            val before = android.os.Debug.getPss()
            val start = android.os.SystemClock.elapsedRealtime()
            val voice = MeloSpeechEngine(directory)
            val loaded = android.os.SystemClock.elapsedRealtime()
            val output = File(context.cacheDir, "tts-evidence").also { it.mkdirs() }
            try {
                listOf(
                    "今天是2026年10月19日，预算137元。银行行长正在检查重庆的项目。",
                    "这是Helix本机语音测试。Hello world. Please stop and replay.",
                ).forEachIndexed { index, text ->
                    val chunks = mutableListOf<FloatArray>()
                    var rate = 0
                    var first = 0L
                    val began = android.os.SystemClock.elapsedRealtime()
                    voice.generate(text, { true }) { samples, sampleRate ->
                        if (first == 0L) first = android.os.SystemClock.elapsedRealtime()
                        rate = sampleRate
                        chunks += samples.copyOf()
                    }
                    val ended = android.os.SystemClock.elapsedRealtime()
                    val pcm = FloatArray(chunks.sumOf { it.size })
                    var offset = 0
                    chunks.forEach {
                        it.copyInto(pcm, offset)
                        offset += it.size
                    }
                    assertTrue(pcm.isNotEmpty() && pcm.all { it.isFinite() })
                    assertTrue(pcm.any { kotlin.math.abs(it) > 0.0001f })
                    assertTrue(GeneratedAudio(pcm, rate).save(File(output, "synthetic-$index.wav").path))
                    val metrics =
                        "fixture=$index loadMs=${loaded - start} " +
                            "firstMs=${first - began} " +
                            "generateMs=${ended - began} " +
                            "durationMs=${pcm.size * 1000L / rate} " +
                            "processPssBeforeKb=$before " +
                            "processPssAfterKb=${android.os.Debug.getPss()}"
                    android.util.Log.i("HelixTtsEvidence", metrics)
                }
            } finally {
                voice.close()
            }
        }

    @Test fun realNativeCallbackCancellationStopsFurtherOutput() {
        var active = true
        var callbacks = 0
        val started = android.os.SystemClock.elapsedRealtime()
        MeloSpeechEngine(directory).use { voice ->
            voice.generate("这是合成取消测试。后续句子不应继续输出。", { active }) { samples, _ ->
                assertTrue(samples.isNotEmpty())
                callbacks++
                active = false
            }
        }
        assertEquals(1, callbacks)
        android.util.Log.i(
            "HelixTtsEvidence",
            "nativeCancelTotalMs=${android.os.SystemClock.elapsedRealtime() - started} callbacks=$callbacks",
        )
    }

    @Test fun missingModelNeverSynthesizesOrDownloadsAutomatically() {
        val root = File(context.cacheDir, "tts-missing-${UUID.randomUUID()}")
        val model = LocalVoiceModel(context, root)
        val service =
            ReadAloudService(
                model,
                { error("Must not load missing model") },
                { error("No focus") },
                { error("No sink") },
            )
        try {
            main { service.play("missing", "Synthetic text") }
            await { service.state.value.phase == ReadAloudPhase.MISSING }
            assertNull(model.downloadedBytes.value)
            assertTrue(!root.exists())
        } finally {
            main(service::close)
            root.deleteRecursively()
        }
    }

    @Test fun cancellationKeepsPhysicalCapacityUntilOriginalEngineExits() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val created = AtomicInteger()
        val alive = AtomicInteger()
        val maximum = AtomicInteger()
        val closed = AtomicInteger()
        val interrupted = AtomicInteger()
        val service =
            ReadAloudService(LocalVoiceModel(context, root), {
                val number = created.incrementAndGet()
                maximum.updateAndGet { maxOf(it, alive.incrementAndGet()) }
                object : SpeechEngine {
                    override fun generate(
                        text: String,
                        keepGoing: () -> Boolean,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        if (number == 1) {
                            consume(floatArrayOf(0.1f), 16_000)
                            entered.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                        } else if (keepGoing()) {
                            consume(floatArrayOf(0.1f), 16_000)
                        }
                    }

                    override fun close() {
                        alive.decrementAndGet()
                        closed.incrementAndGet()
                    }
                }
            }, { AutoCloseable {} }, {
                object : SpeechSink {
                    override fun write(
                        samples: FloatArray,
                        keepGoing: () -> Boolean,
                    ) = Unit

                    override fun finish(keepGoing: () -> Boolean) = Unit

                    override fun interrupt() {
                        interrupted.incrementAndGet()
                    }

                    override fun close() = Unit
                }
            })
        try {
            main { service.play("first", "Synthetic first") }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            main {
                service.play("discarded", "Synthetic discarded")
                service.play("last", "Synthetic last")
            }
            Thread.sleep(100)
            assertEquals(1, created.get())
            assertTrue(interrupted.get() > 0)
            release.countDown()
            await { created.get() == 2 && closed.get() == 2 }
            assertEquals(1, maximum.get())
            assertEquals(0, alive.get())
        } finally {
            release.countDown()
            main(service::close)
        }
    }

    @Test fun deniedAudioFocusDoesNotOpenEngine() {
        val service =
            ReadAloudService(
                LocalVoiceModel(context, root),
                { error("Focus denied") },
                { null },
                { error("No sink") },
            )
        try {
            main { service.play("denied", "Synthetic text") }
            await { service.state.value.phase == ReadAloudPhase.FAILED }
        } finally {
            main(service::close)
        }
    }

    @Test fun unavailableNativeEngineIsExplicitWithoutCloudFallback() {
        val service =
            ReadAloudService(
                LocalVoiceModel(context, root),
                { throw UnsatisfiedLinkError("Injected native linkage failure") },
                { AutoCloseable {} },
                { error("No sink") },
            )
        try {
            main { service.play("unavailable", "Synthetic text") }
            await { service.state.value.phase == ReadAloudPhase.UNAVAILABLE }
        } finally {
            main(service::close)
        }
    }

    @Test fun controlledSilentAudioTrackDrainsWithoutChangingSystemVolume() {
        check(
            android.os.Build.MODEL
                .contains("sdk_gphone"),
        )
        val audio = SpeechAudio(context, volume = 0f)
        audio.sink(16_000).use { sink ->
            sink.write(FloatArray(3200) { 0.01f }) { true }
            sink.finish { true }
            sink.interrupt()
        }
    }

    @Test fun synthesisFailureRetainsErrorAndClosesLease() {
        val closed = AtomicInteger()
        val leases = AtomicInteger()
        val service =
            ReadAloudService(LocalVoiceModel(context, root), {
                object : SpeechEngine {
                    override fun generate(
                        text: String,
                        keepGoing: () -> Boolean,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        error("Injected synth failure")
                    }

                    override fun close() {
                        closed.incrementAndGet()
                    }
                }
            }, { AutoCloseable { leases.incrementAndGet() } }, { error("No audio") })
        try {
            main { service.play("failed", "Synthetic failure") }
            await { service.state.value.phase == ReadAloudPhase.FAILED && leases.get() == 1 }
            assertEquals(1, closed.get())
        } finally {
            main(service::close)
        }
    }

    companion object {
        private lateinit var root: File
        private val directory get() = File(root, "melo-int8")

        @JvmStatic @BeforeClass
        fun prepare(): Unit =
            runBlocking(Dispatchers.IO) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                root = File(instrumentation.targetContext.cacheDir, "tts-test-${UUID.randomUUID()}")
                check(directory.mkdirs())
                LocalVoiceModel(instrumentation.targetContext, root).parts.forEach { part ->
                    instrumentation.context.assets.open("tts-test/${part.name}").use { input ->
                        File(directory, part.name).outputStream().use(input::copyTo)
                    }
                }
                assertNotNull(LocalVoiceModel(instrumentation.targetContext, root).ready())
            }

        @JvmStatic @AfterClass
        fun clean() {
            if (::root.isInitialized) root.deleteRecursively()
        }
    }
}
