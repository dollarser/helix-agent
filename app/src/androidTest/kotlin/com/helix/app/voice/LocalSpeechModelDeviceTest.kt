package com.helix.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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

class LocalSpeechModelDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bytes = "synthetic-model".toByteArray()

    private fun part(name: String = "model.bin") =
        LocalSpeechModel.Part(
            name,
            "https://modelscope.cn/synthetic/model",
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
                LocalSpeechModel(context, root, { uri ->
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
                LocalSpeechModel(
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
                LocalSpeechModel(context, root, { uri ->
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
                        throw java.net.SocketException("cancelled")
                    }

                    override fun close() {
                        release.countDown()
                    }
                }
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val model =
                LocalSpeechModel(context, root, { uri ->
                    calls.incrementAndGet()
                    Connection(uri, input)
                }, listOf(part()))
            val job =
                kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch(Dispatchers.IO) {
                    runCatching { model.download() }
                }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            model.cancelDownload()
            job.cancelAndJoin()
            assertEquals(1, calls.get())
            assertNull(model.ready())
            assertNull(model.downloadedBytes.value)
            assertTrue(root.list().orEmpty().isEmpty())
        }

    @Test fun removingOnlyLockedPartsPreservesUnrelatedFilesAndAllowsDownloadAgain() =
        fixture { root ->
            val model =
                LocalSpeechModel(
                    context,
                    root,
                    { uri -> Connection(uri, ByteArrayInputStream(bytes)) },
                    listOf(part()),
                )
            model.download()
            val unrelated = File(root, "melo-int8/keep.txt").apply { writeText("retain") }
            val otherModel =
                File(root, "unrelated-model/keep.txt").apply {
                    parentFile!!.mkdirs()
                    writeText("other")
                }
            assertEquals(bytes.size.toLong(), model.installedBytes())
            model.removeInstalled()
            assertNull(model.ready())
            assertEquals(0L, model.installedBytes())
            assertEquals("retain", unrelated.readText())
            assertEquals("other", otherModel.readText())
            model.download()
            assertNotNull(model.ready())
            assertEquals("retain", unrelated.readText())
            assertEquals("other", otherModel.readText())
        }

    @Test fun removingModelRejectsRedirectedDirectoryWithoutDeletingTarget() =
        fixture { root ->
            val protected = File(root, "protected").apply { mkdirs() }
            val original = File(protected, "model.bin").apply { writeBytes(bytes) }
            java.nio.file.Files
                .createSymbolicLink(File(root, "melo-int8").toPath(), protected.toPath())
            val model = LocalSpeechModel(context, root, partSource = listOf(part()))
            assertTrue(runCatching { model.removeInstalled() }.isFailure)
            assertTrue(original.readBytes().contentEquals(bytes))
            File(root, "melo-int8").delete()
        }

    @Test fun failedReplacementKeepsPreviouslyVerifiedModelAvailable() =
        fixture { root ->
            var content = bytes
            val model =
                LocalSpeechModel(
                    context,
                    root,
                    { uri -> Connection(uri, ByteArrayInputStream(content)) },
                    listOf(part()),
                )
            model.download()
            content = ByteArray(bytes.size)
            assertTrue(runCatching { model.download() }.isFailure)
            assertNotNull(model.ready())
            assertEquals(bytes.size.toLong(), model.installedBytes())
        }

    @Test fun transientReadRetriesOnceAndStillVerifiesPinnedBytes() =
        fixture { root ->
            var calls = 0
            val interrupted =
                object : InputStream() {
                    override fun read(): Int = throw java.net.SocketException("interrupted")
                }
            val factory: (URI) -> HttpURLConnection = { uri ->
                calls++
                Connection(uri, if (calls == 1) interrupted else ByteArrayInputStream(bytes))
            }
            val model = LocalSpeechModel(context, root, factory, listOf(part()))
            model.download()
            assertEquals(2, calls)
            assertEquals(1, model.recoveryAttempts.value)
            assertNotNull(model.ready())
        }

    @Test fun persistentNetworkFailureStopsAfterOneRetry() =
        fixture { root ->
            var calls = 0
            val factory: (URI) -> HttpURLConnection = {
                calls++
                throw java.net.SocketException("offline")
            }
            val model = LocalSpeechModel(context, root, factory, listOf(part()))
            assertTrue(runCatching { model.download() }.isFailure)
            assertEquals(2, calls)
            assertNull(model.ready())
            assertTrue(root.list().orEmpty().isEmpty())
        }

    @Test fun ttsRemovalWaitsForSharedPhysicalLease() =
        fixture { root ->
            val model = LocalSpeechModel(context, root, partSource = listOf(part()))
            val installed =
                File(root, "melo-int8/model.bin").apply {
                    parentFile!!.mkdirs()
                    writeBytes(bytes)
                }
            val capacity = kotlinx.coroutines.sync.Mutex(locked = true)
            val entered = CountDownLatch(1)
            val service =
                ReadAloudService(
                    model,
                    { error("No synthesis") },
                    { error("No audio focus") },
                    { error("No audio output") },
                    synthesis = capacity,
                )
            coroutineScope {
                val removing =
                    async(Dispatchers.Main) {
                        entered.countDown()
                        service.removeDownloadedModel()
                    }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertTrue(installed.isFile)
                    assertFalse(removing.isCompleted)
                    capacity.unlock()
                    removing.await()
                    assertNull(model.ready())
                    assertEquals(0L, model.installedBytes())
                } finally {
                    if (capacity.isLocked) capacity.unlock()
                    removing.cancelAndJoin()
                    service.close()
                }
            }
        }

    @Test fun asrRemovalWaitsForRecognizerLeaseAndDoesNotDeleteEarly() =
        fixture { root ->
            val model = LocalSpeechModel(context, root, partSource = listOf(part()), kind = LocalSpeechModel.Kind.ASR)
            val installed =
                File(root, "${LocalSpeechModel.Kind.ASR.installation}/model.bin").apply {
                    parentFile!!.mkdirs()
                    writeBytes(bytes)
                }
            val lease = model.acquireAsrUse()
            val entered = CountDownLatch(1)
            coroutineScope {
                val removing =
                    async(Dispatchers.IO) {
                        entered.countDown()
                        model.removeInstalled()
                    }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertTrue(installed.isFile)
                    assertFalse(removing.isCompleted)
                } finally {
                    lease.close()
                }
                removing.await()
                assertNull(model.ready())
            }
        }

    @Test fun missingAsrLoadReleasesUseLease() =
        fixture { root ->
            val model = LocalSpeechModel(context, root, partSource = listOf(part()), kind = LocalSpeechModel.Kind.ASR)
            assertTrue(runCatching { OfflineAsrFactory(model).open() }.isFailure)
            kotlinx.coroutines.withTimeout(2000) { model.acquireAsrUse().close() }
        }

    @Test fun ttsEofDuringUserCancellationIsNotReportedAsFailure() =
        fixture { root ->
            val reading = CountDownLatch(1)
            val released = CountDownLatch(1)
            val input =
                object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        check(released.await(30, TimeUnit.SECONDS))
                        return -1
                    }

                    override fun close() {
                        released.countDown()
                    }
                }
            val model = LocalSpeechModel(context, root, { uri -> Connection(uri, input) }, listOf(part()))
            val service =
                ReadAloudService(
                    model,
                    { error("No synthesis") },
                    { error("No focus") },
                    { error("No output") },
                )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            try {
                instrumentation.runOnMainSync(service::download)
                assertTrue(reading.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync(service::leave)
                kotlinx.coroutines.withTimeout(5000) {
                    while (service.state.value.phase == ReadAloudPhase.DOWNLOADING) kotlinx.coroutines.delay(10)
                }
                assertEquals(ReadAloudPhase.IDLE, service.state.value.phase)
                assertNull(model.ready())
                assertTrue(root.list().orEmpty().isEmpty())
            } finally {
                released.countDown()
                instrumentation.runOnMainSync(service::close)
            }
        }

    @Test fun unsafeMetadataNeverCreatesAFile() =
        fixture { root ->
            assertTrue(
                runCatching { LocalSpeechModel(context, root, partSource = listOf(part("../escape"))) }.isFailure,
            )
            assertTrue(runCatching { LocalSpeechModel(context, root, partSource = listOf(part(), part())) }.isFailure)
            assertFalse(root.exists())
        }
}
