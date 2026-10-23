package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixTheme
import com.helix.app.voice.LocalSpeechModel
import com.helix.app.voice.OfflineAsrModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
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

/** Synthetic model bytes and an injected transport only. No network, microphone, or permission grant. */
class OfflineAsrDownloadDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val data = "synthetic-asr-model".toByteArray()

    private class Connection(
        uri: URI,
        private val input: InputStream,
    ) : HttpURLConnection(uri.toURL()) {
        override fun connect() = Unit

        override fun disconnect() = input.close()

        override fun usingProxy() = false

        override fun getResponseCode() = 200

        override fun getInputStream() = input
    }

    private fun files(
        root: File,
        input: () -> InputStream,
    ): LocalSpeechModel =
        LocalSpeechModel(
            context,
            root,
            { uri -> Connection(uri, input()) },
            listOf(
                LocalSpeechModel.Part(
                    "model.bin",
                    "https://huggingface.co/synthetic/asr",
                    data.size.toLong(),
                    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) },
                ),
            ),
            LocalSpeechModel.Kind.ASR,
        )

    private fun fixture(test: (File) -> Unit) {
        check(context.packageName.endsWith(".asrvalidation"))
        val root = File(context.cacheDir, "asr-download-${UUID.randomUUID()}")
        try {
            test(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Composable
    @Suppress("FunctionName")
    private fun Gate(
        model: OfflineAsrModel,
        entered: () -> Unit,
    ) {
        HelixTheme {
            OfflineAsrGateContent(model, {}) {
                LaunchedEffect(Unit) { entered() }
                androidx.compose.material3.Text(
                    "capture permitted",
                    androidx.compose.ui.Modifier
                        .testTag("capture"),
                )
            }
        }
    }

    @Test fun explicitDownloadVerifiesAndStillRequiresContinueBeforeCapture() =
        fixture { root ->
            var calls = 0
            var entered = 0
            val model =
                OfflineAsrModel(
                    files(root) {
                        calls++
                        ByteArrayInputStream(data)
                    },
                )
            compose.setContent { Gate(model) { entered++ } }
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.MISSING }
            assertEquals(0, calls)
            compose.onNodeWithTag("capture").assertDoesNotExist()
            compose.onNodeWithTag("offline-asr-download").performClick()
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.READY }
            compose.onNodeWithTag("offline-asr-continue").assertIsDisplayed()
            assertEquals(0, entered)
            assertEquals(1, calls)
            assertNotNull(runBlocking { files(root) { error("Reopen must not download") }.ready() })
            assertFalse(File(root, "melo-int8").exists())
            compose.onNodeWithTag("offline-asr-continue").performClick()
            compose.waitUntil(5000) { entered == 1 }
        }

    @Test fun wrongHashRemainsUnavailableUntilAnExplicitRetry() =
        fixture { root ->
            var bytes = ByteArray(data.size)
            var calls = 0
            val model =
                OfflineAsrModel(
                    files(root) {
                        calls++
                        ByteArrayInputStream(bytes)
                    },
                )
            compose.setContent { Gate(model) { error("No automatic capture") } }
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.MISSING }
            compose.onNodeWithTag("offline-asr-download").performClick()
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.FAILED }
            assertNull(runBlocking { model.files.ready() })
            assertEquals(1, calls)
            bytes = data
            compose.onNodeWithTag("offline-asr-download").performClick()
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.READY }
            assertEquals(2, calls)
            compose.onNodeWithTag("capture").assertDoesNotExist()
        }

    @Test fun cancelDisconnectsAndDoesNotPublishALateInstall() =
        fixture { root ->
            val reading = CountDownLatch(1)
            val released = CountDownLatch(1)
            val input =
                object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        check(released.await(60, TimeUnit.SECONDS))
                        return -1
                    }

                    override fun close() {
                        released.countDown()
                    }
                }
            val model = OfflineAsrModel(files(root) { input })
            compose.setContent { Gate(model) { error("No automatic capture") } }
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.MISSING }
            compose.onNodeWithTag("offline-asr-download").performClick()
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertEquals(0L, model.downloadedBytes.value)
            compose.onNodeWithTag("offline-asr-download-cancel").assertIsDisplayed().performClick()
            compose.waitUntil(5000) { model.phase.value != OfflineAsrModel.Phase.DOWNLOADING }
            assertEquals(OfflineAsrModel.Phase.MISSING, model.phase.value)
            assertNull(runBlocking { model.files.ready() })
            assertNull(model.downloadedBytes.value)
            assertTrue(root.list().orEmpty().isEmpty())
        }

    @Test fun resourceRemovalRequiresConfirmationAndPreservesOtherSpeechModels() =
        fixture { root ->
            val model = OfflineAsrModel(files(root) { ByteArrayInputStream(data) })
            runBlocking { model.files.download() }
            val keep =
                File(root, "melo-int8/keep.txt").apply {
                    parentFile!!.mkdirs()
                    writeText("keep")
                }
            compose.setContent { HelixTheme { OfflineAsrModelPanel(model) } }
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.READY }
            compose.onNodeWithTag("offline-asr-remove").performClick()
            assertNotNull(runBlocking { model.files.ready() })
            compose.onNodeWithText(context.getString(com.helix.app.R.string.common_cancel)).performClick()
            assertNotNull(runBlocking { model.files.ready() })
            compose.onNodeWithTag("offline-asr-remove").performClick()
            compose.onNodeWithTag("offline-asr-remove-confirm").performClick()
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.MISSING }
            assertNull(runBlocking { model.files.ready() })
            assertEquals(0L, model.installedBytes.value)
            assertEquals("keep", keep.readText())
        }

    @Test fun closingWhileRemovalWaitsPreservesModelAndDoesNotReportFailure() =
        fixture { root ->
            val model = OfflineAsrModel(files(root) { ByteArrayInputStream(data) })
            runBlocking { model.files.download() }
            val lease = runBlocking { model.files.acquireAsrUse() }
            try {
                compose.setContent { HelixTheme { OfflineAsrModelPanel(model) } }
                compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.READY }
                compose.onNodeWithTag("offline-asr-remove").performClick()
                compose.onNodeWithTag("offline-asr-remove-confirm").performClick()
                compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.CHECKING }
                compose.runOnIdle { model.cancel() }
                compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.READY }
                assertNotNull(runBlocking { model.files.ready() })
                assertEquals(data.size.toLong(), model.installedBytes.value)
            } finally {
                lease.close()
            }
        }

    @Test fun connectionFailureIsVisibleAndDoesNotRetryItself() =
        fixture { root ->
            var calls = 0
            val model =
                OfflineAsrModel(
                    files(root) {
                        calls++
                        throw java.io.IOException("synthetic disconnected")
                    },
                )
            compose.setContent { Gate(model) { error("No capture after a network failure") } }
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.MISSING }
            compose.onNodeWithTag("offline-asr-download").performClick()
            compose.waitUntil(5000) { model.phase.value == OfflineAsrModel.Phase.FAILED }
            compose.onNodeWithTag("offline-asr-download").assertIsDisplayed()
            assertEquals(1, calls)
            assertNull(runBlocking { model.files.ready() })
        }
}
