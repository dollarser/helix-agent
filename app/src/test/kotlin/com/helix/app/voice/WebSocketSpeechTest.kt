package com.helix.app.voice

import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketConnector
import com.helix.provider.api.wire.SocketMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WebSocketSpeechTest {
    private class Fake(
        rate: Int,
    ) : SocketConnection {
        val incoming = LinkedBlockingQueue<SocketMessage>()
        val texts = mutableListOf<String>()
        val binary = mutableListOf<ByteArray>()

        @Volatile var closed = false
        var onText: (String) -> Unit = {}

        init {
            json("{\"type\":\"ready\",\"sample_rate\":$rate,\"channels\":1,\"format\":\"s16le\"}")
        }

        fun json(text: String) {
            incoming.add(SocketMessage.Text(text))
        }

        override fun sendText(value: String) {
            check(!closed)
            texts.add(value)
            onText(value)
        }

        override fun sendBinary(value: ByteArray) {
            check(!closed)
            binary.add(value)
        }

        override fun receive(timeoutMillis: Long): SocketMessage? {
            check(!closed)
            return incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        }

        override fun close() {
            closed = true
            incoming.offer(SocketMessage.Closed(1006))
        }

        fun connector() = SocketConnector { _, _ -> this }
    }

    @Test fun cumulativeSnapshotsReplaceAndFinalFlushesExactlyOnce() {
        val wire = Fake(16000)
        val asr = WebSocketAsr("test", wire.connector())
        wire.json("{\"type\":\"partial\",\"delta\":\"你\",\"text\":\"你\"}")
        assertEquals("你", asr.send(floatArrayOf(0f)))
        wire.json("{\"type\":\"partial\",\"delta\":\"好\",\"text\":\"你好\"}")
        assertEquals("你好", asr.send(floatArrayOf(1f)))
        wire.onText = { wire.json("{\"type\":\"final\",\"delta\":\"你好\",\"text\":\"你好你好\"}") }
        assertEquals("你好你好", asr.finish())
        assertThrows(IllegalStateException::class.java) { asr.finish() }
        assertEquals(listOf("{\"type\":\"end\"}"), wire.texts)
        assertArrayEquals(byteArrayOf(0, 0), wire.binary[0])
        assertArrayEquals(byteArrayOf(-1, 127), wire.binary[1])
        asr.close()
    }

    @Test fun deltaOnlyAppendsWithoutDeletingGenuineRepetition() {
        val wire = Fake(16000)
        val asr = WebSocketAsr("test", wire.connector())
        wire.json("{\"type\":\"partial\",\"delta\":\"好\"}")
        assertEquals("好", asr.send(floatArrayOf(0f)))
        wire.onText = { wire.json("{\"type\":\"final\",\"delta\":\"好\"}") }
        assertEquals("好好", asr.finish())
        asr.close()
    }

    @Test fun normalCloseCannotReplaceFinalAndBusyIsDistinctFromFailure() {
        for (code in listOf(1000, 1013, 1011)) {
            val wire = Fake(16000)
            val asr = WebSocketAsr("test", wire.connector())
            wire.incoming.add(SocketMessage.Closed(code))
            assertEquals(code, assertThrows(SpeechSocketFailure::class.java) { asr.finish() }.closeCode)
            asr.close()
        }
    }

    @Test fun wrongReadyRejectsBeforeAnyAudioIsSent() {
        val wire = Fake(24000)
        assertThrows(IllegalArgumentException::class.java) { WebSocketAsr("test", wire.connector()) }
        assertTrue(wire.closed)
        assertTrue(wire.binary.isEmpty())
    }

    @Test fun cancellationDropsLateTranscriptAndNewConnectionStartsEmpty() {
        val old = Fake(16000)
        val asr = WebSocketAsr("test", old.connector())
        asr.close()
        old.json("{\"type\":\"final\",\"text\":\"late\"}")
        assertThrows(IllegalStateException::class.java) { asr.send(floatArrayOf(0f)) }
        val fresh = Fake(16000)
        val restarted = WebSocketAsr("test", fresh.connector())
        assertEquals("", restarted.send(floatArrayOf(0f)))
        restarted.close()
    }

    @Test fun finalTimeoutSendsEndOnceAndExplicitNewSessionDoesNotReplayAudio() {
        val clock =
            java.util.concurrent.atomic
                .AtomicLong()
        val first = Fake(16000)
        val asr = WebSocketAsr("test", first.connector()) { clock.getAndAdd(180_000_000_001L) }
        asr.send(floatArrayOf(0f))
        assertThrows(java.io.IOException::class.java) { asr.finish() }
        assertThrows(IllegalStateException::class.java) { asr.finish() }
        asr.close()
        assertTrue(first.closed)
        assertEquals(1, first.binary.size)
        assertEquals(listOf("{\"type\":\"end\"}"), first.texts)
        val next = Fake(16000)
        val fresh = WebSocketAsr("test", next.connector())
        assertEquals("", fresh.send(floatArrayOf(0f)))
        fresh.close()
        assertEquals(1, next.binary.size)
    }

    @Test fun busyAndDisconnectNeverReconnectImplicitly() {
        for (code in listOf(1013, 1006, 1011)) {
            val first = Fake(16000)
            var connections = 0
            val connector =
                SocketConnector { _, _ ->
                    connections++
                    first
                }
            val asr = WebSocketAsr("test", connector)
            first.incoming.add(SocketMessage.Closed(code))
            assertEquals(code, assertThrows(SpeechSocketFailure::class.java) { asr.finish() }.closeCode)
            asr.close()
            assertTrue(first.closed)
            assertEquals(1, connections)
            assertEquals(0, first.binary.size)
        }
    }

    @Test fun ttsTimeoutClosesTheConnection() {
        val clock =
            java.util.concurrent.atomic
                .AtomicLong()
        val wire = Fake(24000)
        val engine = WebSocketTts("test", wire.connector()) { clock.getAndAdd(900_000_000_001L) }
        assertThrows(java.io.IOException::class.java) {
            engine.generateStream({
                CountDownLatch(1).await()
                null
            }, { true }) { _, _ -> fail("No audio expected") }
        }
        assertTrue(wire.closed)
        assertTrue(wire.binary.isEmpty())
        engine.close()
    }

    @Test fun ttsReceivesAudioWhileInputRemainsOpenOnOneConnection() {
        val wire = Fake(24000)
        val audio = CountDownLatch(1)
        var index = 0
        wire.onText = {
            if (it.contains("end")) {
                wire.json("{\"type\":\"done\"}")
            } else {
                wire.incoming.add(SocketMessage.Binary(byteArrayOf(0, 64)))
            }
        }
        val engine = WebSocketTts("test", wire.connector())
        val heard = mutableListOf<Float>()
        engine.generateStream({
            when (index++) {
                0 -> {
                    "你好，"
                }

                1 -> {
                    check(audio.await(2, TimeUnit.SECONDS))
                    "世界。"
                }

                else -> {
                    null
                }
            }
        }, { true }) { pcm, rate ->
            assertEquals(24000, rate)
            heard.addAll(pcm.toList())
            audio.countDown()
        }
        assertEquals(listOf(0.5f, 0.5f), heard)
        assertEquals(3, wire.texts.size)
        assertTrue(wire.closed)
    }

    @Test fun ttsFailureInterruptsBlockedIncrementalInput() {
        val wire = Fake(24000)
        val waiting = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result =
                worker.submit<Boolean> {
                    try {
                        WebSocketTts("test", wire.connector()).generateStream({
                            waiting.countDown()
                            CountDownLatch(1).await()
                            null
                        }, { true }) { _, _ -> fail("late audio") }
                        false
                    } catch (_: SpeechSocketFailure) {
                        true
                    }
                }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            wire.incoming.add(SocketMessage.Closed(1011))
            assertTrue(result.get(3, TimeUnit.SECONDS))
            assertTrue(wire.closed)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun ttsDoesNotAcceptTruncatedOddPcmOrCloseWithoutDone() {
        for (odd in listOf(true, false)) {
            val wire = Fake(24000)
            wire.onText = {
                if (it.contains("end")) {
                    if (odd) {
                        wire.incoming.add(SocketMessage.Binary(byteArrayOf(0, 0, 1)))
                        wire.json("{\"type\":\"done\"}")
                    } else {
                        wire.incoming.add(SocketMessage.Closed(1000))
                    }
                }
            }
            assertThrows(Exception::class.java) {
                WebSocketTts("test", wire.connector()).generate("测试", { true }) { _, _ -> }
            }
            assertTrue(wire.closed)
        }
    }

    @Test fun websocketSettingsRequireOfficialAccountAndPreserveCurrentHttpFormat() {
        val lines =
            com.helix.app.internal
                .InMemoryLineStore()
        val store = SpeechSettingsStore(lines)
        val settings =
            SpeechServiceSettings(
                true,
                "wss://asr.sunlingzhang.com/ws/asr",
                "wss://tts.sunlingzhang.com/ws/tts",
                true,
                true,
            )
        store.save(settings)
        assertEquals(settings, store.read())
        assertThrows(IllegalArgumentException::class.java) { store.save(settings.copy(useAccountLogin = false)) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { store.save(settings.copy(asrEndpoint = "wss://evil.test/ws/asr")) }
        lines.setLines(
            "settings",
            listOf(
                "account-v1",
                "true",
                "https://asr.sunlingzhang.com/v1/audio/transcriptions",
                "https://tts.sunlingzhang.com/v1/audio/speech",
                "true",
                "false",
            ),
        )
        val http = store.read()
        assertTrue(http.httpEnabled && !http.webSocket && http.useAccountLogin)
        http.validate()
    }

    @Test fun ttsCancellationUnblocksIncrementalInputAndRejectsLateAudio() {
        val wire = Fake(24000)
        val engine = WebSocketTts("test", wire.connector())
        val waiting = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val heard =
            java.util.concurrent.atomic
                .AtomicInteger()
        try {
            val result =
                worker.submit<Boolean> {
                    try {
                        engine.generateStream({
                            waiting.countDown()
                            CountDownLatch(1).await()
                            null
                        }, { true }) { _, _ -> heard.incrementAndGet() }
                        false
                    } catch (_: java.io.IOException) {
                        true
                    } catch (_: IllegalStateException) {
                        true
                    }
                }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            engine.interrupt()
            wire.incoming.add(SocketMessage.Binary(byteArrayOf(0, 64)))
            assertTrue(result.get(3, TimeUnit.SECONDS))
            assertEquals(0, heard.get())
            assertTrue(wire.closed)
        } finally {
            worker.shutdownNow()
        }
    }
}
