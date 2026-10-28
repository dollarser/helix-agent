package com.helix.app.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.HelixTheme
import com.helix.app.account.OfficialServices
import com.helix.app.account.speechSocket
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.ui.ComposerActions
import com.helix.app.ui.ConversationComposer
import com.helix.app.ui.InlineVoiceInput
import com.helix.provider.api.wire.OkHttpWireClient
import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketConnector
import com.helix.provider.api.wire.SocketHandshakeFailure
import com.helix.provider.api.wire.SocketMessage
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit existing-account emulator acceptance. No login/logout, LLM, microphone or speaker. */
class ExistingAccountSpeechDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val application get() = context.applicationContext as HelixApplication
    private val account get() = application.account
    private val result get() = File(context.cacheDir, "existing-account-speech-result.txt")
    private val failures = mutableListOf<String>()
    private val posts = AtomicInteger()
    private var quotaBlocked = false
    private val expected = "你好，这是模拟器语音接入测试。"

    private fun record(value: String) = result.appendText(value + "\n")

    private fun <T> attempt(
        name: String,
        action: () -> T,
    ): T? {
        if (quotaBlocked) {
            record("$name=blocked_by_quota_response")
            failures += name
            return null
        }
        record("$name=started")
        return try {
            action().also { record("$name=passed") }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            record("$name=failed:${failure.javaClass.simpleName}")
            failures += name
            null
        } catch (_: AssertionError) {
            record("$name=failed:assertion")
            failures += name
            null
        }
    }

    private fun wire(name: String): WireClient =
        object : WireClient {
            private val delegate = OkHttpWireClient(followRedirects = false, readTimeoutMillis = 90000)

            override suspend fun open(request: WireRequest): WireResponse {
                check(request.url.startsWith(OfficialServices.INFERENCE_BASE_URL + "/"))
                if (request.method == "POST") posts.incrementAndGet()
                val response = delegate.open(request)
                record("$name.http=${response.status}")
                if (response.status == 402 || response.status == 429) quotaBlocked = true
                return response
            }
        }

    @Test fun existingAccountFileAndStreamingSpeechWithoutMicrophone() {
        val args = InstrumentationRegistry.getArguments()
        val approved = args.getString("existingAccountSpeechApproved")
        assumeTrue("Explicit existing-account speech profile not supplied", approved != null)
        check(approved == "true")
        check(context.packageName == "com.helix.agent.developer" && android.os.Build.HARDWARE == "ranchu")
        result.writeText("scope=existing-account,synthetic-audio,no-llm,no-microphone,no-logout\n")
        val authenticated =
            attempt("authentication") {
                runBlocking {
                    account.restore()
                    record("localAccount=${account.status.value}")
                    check(account.signedIn.value) { "Existing account requires login" }
                    val response =
                        account
                            .wire(OfficialServices.INFERENCE_BASE_URL + "/models", wire("authentication"))
                            .open(WireRequest("GET", OfficialServices.INFERENCE_BASE_URL + "/models", emptyMap(), null))
                    try {
                        check(response.status == 200)
                    } finally {
                        response.body.close()
                    }
                }
            }
        check(authenticated != null) { "Authentication failed; see sanitized status record" }
        val httpAudio = attempt("httpTts") { fileTts() }
        val wsAudio = attempt("wsTts") { socketTts() }
        val audio = httpAudio ?: wsAudio
        if (audio != null) {
            attempt("wsAsr") { socketAsr(audio) }
            attempt("httpAsrComposer") { composerAsr(audio) }
        } else {
            record("asr=blocked_by_missing_synthetic_audio")
            failures += "asr"
        }
        attempt("wsCancel") {
            val closed = AtomicBoolean()
            WebSocketAsr(OfficialServices.asr(true), socket("wsCancel", OfficialServices.asr(true), closed)).use { }
            check(closed.get())
            record("wsCancel.server_execution_outcome=not_claimed")
        }
        record("authenticatedSessionRetained=${account.signedIn.value}")
        check(failures.isEmpty()) { "Failed stages: ${failures.joinToString()}" }
    }

    private fun fileTts(): FloatArray {
        val audio = mutableListOf<Float>()
        var rate = 0
        val settings = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true)
        HttpWavTts(settings.session(settings.ttsEndpoint, account, wire("httpTts"))).use {
            it.stream(expected) { pcm, sampleRate ->
                rate = sampleRate
                audio.addAll(pcm.toList())
                check(audio.size <= rate * 12)
            }
        }
        check(audio.isNotEmpty() && audio.any { kotlin.math.abs(it) > 0.001f })
        record("httpTts.rate=$rate,frames=${audio.size}")
        return resample(audio, rate)
    }

    private fun socket(
        name: String,
        url: String,
        closed: AtomicBoolean,
    ): SocketConnector =
        SocketConnector { target, headers ->
            check(target == url && headers.isEmpty())
            val delegate =
                try {
                    account.speechSocket(url).connect(target, headers)
                } catch (failure: SocketHandshakeFailure) {
                    record("$name.handshake=${failure.status}")
                    if (failure.status == 402 || failure.status == 429) quotaBlocked = true
                    throw failure
                }
            record("$name.handshake=opened")
            object : SocketConnection {
                override fun sendText(value: String) = delegate.sendText(value)

                override fun sendBinary(value: ByteArray) = delegate.sendBinary(value)

                override fun receive(timeoutMillis: Long): SocketMessage? = delegate.receive(timeoutMillis)

                override fun close() {
                    delegate.close()
                    closed.set(true)
                }
            }
        }

    private fun socketTts(): FloatArray {
        val audio = mutableListOf<Float>()
        val closed = AtomicBoolean()
        var index = 0
        WebSocketTts(OfficialServices.tts(true), socket("wsTts", OfficialServices.tts(true), closed)).use {
            it.generateStream({
                when (index++) {
                    0 -> "你好，"
                    1 -> "这是模拟器语音接入测试。"
                    else -> null
                }
            }, { true }) { pcm, rate ->
                check(rate == 24000)
                audio.addAll(pcm.toList())
                check(audio.size <= rate * 12)
            }
        }
        check(closed.get() && audio.isNotEmpty())
        record("wsTts.frames=${audio.size},socketClosed=true")
        return resample(audio, 24000)
    }

    private fun socketAsr(audio: FloatArray) {
        val closed = AtomicBoolean()
        WebSocketAsr(OfficialServices.asr(true), socket("wsAsr", OfficialServices.asr(true), closed)).use { asr ->
            audio.asList().chunked(1600).forEach {
                asr.send(it.toFloatArray())
                Thread.sleep(100)
            }
            val text = asr.finish()
            check(text.isNotBlank())
            record("wsAsr.syntheticTranscript=$text")
            record("wsAsr.exactSyntheticMatch=${normalize(text) == normalize(expected)}")
        }
        check(closed.get())
    }

    private fun resample(
        audio: List<Float>,
        rate: Int,
    ): FloatArray =
        FloatArray((audio.size.toLong() * 16000 / rate).toInt()) { i ->
            val position = i.toDouble() * rate / 16000
            val first = position.toInt().coerceAtMost(audio.lastIndex)
            val second = minOf(first + 1, audio.lastIndex)
            audio[first] + (audio[second] - audio[first]) * (position - first).toFloat()
        }

    private fun normalize(text: String) = text.filter { it.isLetterOrDigit() }

    @Suppress("LongMethod") // One real ASR response is consumed by the production composer/adapter fixture.
    private fun composerAsr(audio: FloatArray) {
        val base = "保留的合成草稿"
        var draft by mutableStateOf(base)
        var preview by mutableStateOf<String?>(null)
        var open by mutableStateOf(true)
        var cancelling = false
        var sent = 0
        val sourceClosed = AtomicBoolean()
        val sourceStarted = AtomicBoolean()
        val consent = SpeechConsentStore(InMemoryLineStore())
        consent.approve("asr", OfficialServices.asr(false), account.identity)
        consent.approve("asr", OfficialServices.asr(false), "custom-service")
        val settings = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true)
        val factory =
            CloudAsrFactory(OfficialServices.asr(false), false) {
                HttpFileAsr(settings.session(settings.asrEndpoint, account, wire("httpAsr")))
            }
        compose.setContent {
            HelixTheme {
                ConversationComposer(
                    input = preview ?: draft,
                    onInput = { draft = it },
                    isSending = false,
                    hasAttachments = false,
                    voiceActive = open,
                    voiceContent = {
                        if (open) {
                            InlineVoiceInput(
                                onDismiss = {
                                    preview = null
                                    open = false
                                },
                                onAccept = { draft = VoiceInputMapper.appendDraft(base, it) },
                                onPreview = { preview = VoiceInputMapper.appendDraft(base, it) },
                                permissionChecker = { true },
                                suppliedConsent = consent,
                                captureFactory = { ctx, scope ->
                                    lateinit var capture: VoiceCapture
                                    capture =
                                        VoiceCapture(ctx, scope, factory) {
                                            SyntheticPcm(
                                                audio,
                                                cancelling,
                                                sourceStarted,
                                                sourceClosed,
                                            ) { capture.finish() }
                                        }
                                    capture
                                },
                            )
                        }
                    },
                    actions = ComposerActions(onFile = {}, onVoice = {}, onSend = { sent++ }, onStop = {}),
                )
            }
        }
        compose.waitUntil(120000) {
            !open || compose.onAllNodesWithTag("voice-retry").fetchSemanticsNodes().isNotEmpty()
        }
        check(!open && draft.startsWith(base) && draft != base)
        compose.waitUntil(10000) { sourceClosed.get() }
        compose.onNodeWithTag("chat-input").assertIsEnabled().performTextReplacement("已编辑的合成转写")
        check(draft == "已编辑的合成转写" && sent == 0)
        record("httpAsrComposer.editableDraft=true,automaticSend=false")
        val before = posts.get()
        compose.runOnIdle {
            sourceClosed.set(false)
            cancelling = true
            open = true
        }
        compose.waitUntil(10000) {
            sourceStarted.get() && compose.onAllNodesWithTag("voice-cancel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("voice-cancel").performClick()
        compose.waitUntil(10000) { sourceClosed.get() }
        check(draft == "已编辑的合成转写" && sent == 0 && posts.get() == before)
        record("httpAsrComposer.cancelRestoredDraft=true,sourceReleased=true,noUploadOnCancel=true")
    }

    private class SyntheticPcm(
        private val audio: FloatArray,
        private val blocking: Boolean,
        private val started: AtomicBoolean,
        private val closed: AtomicBoolean,
        private val finish: () -> Unit,
    ) : PcmCapture {
        private var index = 0
        private val stopped = CountDownLatch(1)

        override fun read(samples: ShortArray): Int {
            if (blocking) {
                started.set(true)
                check(stopped.await(15, TimeUnit.SECONDS))
                return 1
            }
            val count = minOf(samples.size, audio.size - index)
            check(count > 0)
            repeat(count) { samples[it] = (audio[index++] * 32767).toInt().toShort() }
            if (index == audio.size) finish()
            return count
        }

        override fun stop() {
            stopped.countDown()
        }

        override fun close() {
            stopped.countDown()
            closed.set(true)
        }
    }
}
