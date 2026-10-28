package com.helix.app.proot

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.account.speechSocket
import com.helix.app.application.diagnostics.DiagnosticRedaction
import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketMessage
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** One authorized fixed synthetic input, serial HTTP/WS. Transport completion is not a quality pass. */
class AsrSameSampleDeviceTest {
    @Test
    @Suppress("LongMethod") // One serial bounded probe keeps byte identity and original responses together.
    fun retainOriginalHttpAndWsResponses(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("asrSameSample")
            assumeTrue("Explicit synthetic replay absent", flag != null)
            require(flag == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            require(app.packageName.endsWith(".asrvalidation"))
            app.awaitAppContainer()
            check(app.account.signedIn.value)
            val directory = File(app.cacheDir, "fixed-asr")
            val pcm = File(directory, "input.pcm").readBytes()
            val wav = File(directory, "input.wav").readBytes()
            assertEquals("424d11fb9fda2317df33875b2bd3251b5760cd9bdcfdd61c04cc8067c1b40080", hash(pcm))
            assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
            val httpUrl = "https://api.sunlingzhang.com/v1/audio/transcriptions"
            val boundary = "helix-synthetic-fixed-sample"
            val multipart =
                (
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                        "Content-Type: audio/wav\r\n\r\n"
                ).toByteArray() + wav + "\r\n--$boundary--\r\n".toByteArray()
            val response =
                withTimeout(60_000) {
                    app.account.wire(httpUrl).open(
                        WireRequest(
                            "POST",
                            httpUrl,
                            mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                            multipart,
                        ),
                    )
                }
            val body =
                try {
                    withTimeout(60_000) { response.body.bytes() }
                } finally {
                    response.body.close()
                }
            File(directory, "http-response.json").writeText(
                com.helix.app.application.diagnostics.DiagnosticRedaction
                    .redact(body.toString(Charsets.UTF_8), emptySet()),
            )
            assertEquals(200, response.status)
            val httpText =
                Json
                    .parseToJsonElement(
                        body.toString(Charsets.UTF_8),
                    ).jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            val events = File(directory, "ws-events.txt").apply { writeText("") }
            val frames = File(directory, "frames.txt").apply { writeText("") }
            val url = "wss://api.sunlingzhang.com/ws/asr"
            val final =
                app.account.speechSocket(url).connect(url, emptyMap()).use { socket ->
                    val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
                    val ready = receiveType(socket, events, "ready", deadline)
                    assertEquals("16000", ready.getValue("sample_rate").jsonPrimitive.content)
                    assertEquals("1", ready.getValue("channels").jsonPrimitive.content)
                    assertEquals("s16le", ready.getValue("format").jsonPrimitive.content)
                    pcm.asList().chunked(3200).forEachIndexed { index, values ->
                        val bytes = values.toByteArray()
                        socket.sendBinary(bytes)
                        frames.appendText("$index ${bytes.size} ${hash(bytes)}\n")
                        Thread.sleep(100)
                    }
                    socket.sendText("{\"type\":\"end\"}")
                    receiveType(socket, events, "final", deadline).getValue("text").jsonPrimitive.content
                }
            check(!httpText.isBlank() && !final.isNullOrBlank())
            File(directory, "result.json").writeText(
                buildJsonObject {
                    put("pcmSha256", hash(pcm))
                    put("sampleRate", 16000)
                    put("bytes", pcm.size)
                    put("expected", "你好，这是模拟器语音接入测试。")
                    put("httpText", httpText)
                    put("wsText", final)
                    put("routesAgree", httpText == final)
                    put("transportCompleted", true)
                    put("quality", "Review expected versus actual; no word replacement or relaxed quality assertion")
                }.toString(),
            )
        }

    private fun receiveType(
        socket: SocketConnection,
        events: File,
        type: String,
        deadline: Long,
    ): JsonObject {
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            when (val message = socket.receive(1000)) {
                is SocketMessage.Text -> {
                    events.appendText(
                        com.helix.app.application.diagnostics.DiagnosticRedaction
                            .redact(message.value, emptySet()) + "\n",
                    )
                    val value = Json.parseToJsonElement(message.value).jsonObject
                    check(value["type"]?.jsonPrimitive?.content != "error") { "ASR service returned an error" }
                    if (value["type"]?.jsonPrimitive?.content == type) return value
                }

                is SocketMessage.Closed -> {
                    error("ASR closed before $type: ${message.code}")
                }

                else -> {
                    Unit
                }
            }
        }
        error("ASR $type timeout")
    }

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}
