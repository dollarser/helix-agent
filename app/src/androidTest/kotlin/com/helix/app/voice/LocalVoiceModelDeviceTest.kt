package com.helix.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalVoiceModelDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bytes = "synthetic-model".toByteArray()

    private fun part(name: String = "model.bin") =
        LocalVoiceModel.Part(
            name,
            "https://huggingface.co/synthetic/model",
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )

    private class Connection(
        uri: URI,
        val stream: InputStream,
        val status: Int = 200,
        val location: String? = null,
    ) : HttpURLConnection(uri.toURL()) {
        var disconnected = false

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
            stream.close()
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getInputStream() = stream

        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }

    private fun fixture(block: suspend (File) -> Unit) =
        runBlocking {
            val root = File(context.cacheDir, "tts-download-${UUID.randomUUID()}")
            try {
                block(root)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test fun verifiedInstallIsLocalAndDoesNotFetchAgain() =
        fixture { root ->
            var calls = 0
            val model =
                LocalVoiceModel(context, root, { uri ->
                    calls++
                    Connection(uri, ByteArrayInputStream(bytes))
                }, listOf(part()))
            assertNull(model.ready())
            assertEquals(0, calls)
            model.download()
            assertNotNull(model.ready())
            assertNotNull(model.ready())
            assertEquals(1, calls)
            assertEquals(listOf("melo-int8"), root.list()?.toList())
            File(root, "melo-int8/model.bin").writeText("corrupt")
            assertNull(model.ready())
            assertEquals(1, calls)
        }

    @Test fun overLimitAndWrongHashRemainUnavailableThenExplicitRetryWorks() =
        fixture { root ->
            var content = bytes + byteArrayOf(1)
            val model =
                LocalVoiceModel(
                    context,
                    root,
                    { uri -> Connection(uri, ByteArrayInputStream(content)) },
                    listOf(part()),
                )
            assertTrue(runCatching { model.download() }.isFailure)
            assertNull(model.ready())
            assertTrue(root.list().orEmpty().isEmpty())
            content = ByteArray(bytes.size)
            assertTrue(runCatching { model.download() }.isFailure)
            assertNull(model.ready())
            content = bytes
            model.download()
            assertNotNull(model.ready())
        }

    @Test fun redirectOutsidePinnedHostFamilyIsRejectedBeforeConnection() =
        fixture { root ->
            var calls = 0
            val model =
                LocalVoiceModel(context, root, { uri ->
                    calls++
                    Connection(uri, ByteArrayInputStream(byteArrayOf()), 302, "https://example.com/model")
                }, listOf(part()))
            assertTrue(runCatching { model.download() }.isFailure)
            assertEquals(1, calls)
            assertNull(model.ready())
        }

    @Test fun cancellationDisconnectsAndRemovesIncompleteStaging() =
        fixture { root ->
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val input =
                object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return -1
                    }

                    override fun close() {
                        release.countDown()
                    }
                }
            val model = LocalVoiceModel(context, root, { uri -> Connection(uri, input) }, listOf(part()))
            val job =
                kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch(Dispatchers.IO) {
                    runCatching { model.download() }
                }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            model.cancelDownload()
            job.cancelAndJoin()
            assertNull(model.ready())
            assertNull(model.downloadedBytes.value)
            assertTrue(root.list().orEmpty().isEmpty())
        }

    @Test fun unsafeMetadataNeverCreatesAFile() =
        fixture { root ->
            assertTrue(runCatching { LocalVoiceModel(context, root, partSource = listOf(part("../escape"))) }.isFailure)
            assertTrue(runCatching { LocalVoiceModel(context, root, partSource = listOf(part(), part())) }.isFailure)
            assertFalse(root.exists())
        }
}
