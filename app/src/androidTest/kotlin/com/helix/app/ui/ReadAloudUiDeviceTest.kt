package com.helix.app.ui

import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.MessageUi
import com.helix.app.voice.LocalVoiceModel
import com.helix.app.voice.ReadAloudPhase
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.SpeechAudio
import com.helix.app.voice.SpeechEngine
import com.helix.app.voice.SpeechSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ReadAloudUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(
        ready: Boolean = true,
        realFocus: Boolean = false,
        block: (ReadAloudService, AtomicInteger) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "tts-ui-${UUID.randomUUID()}")
        val data = "synthetic".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val part =
            LocalVoiceModel.Part(
                "model.bin",
                "https://huggingface.co/synthetic",
                data.size.toLong(),
                hash,
            )
        if (ready) {
            File(root, "melo-int8").mkdirs()
            File(root, "melo-int8/model.bin").writeBytes(data)
        }
        val model = LocalVoiceModel(context, root, { error("No automatic network request") }, listOf(part))
        val closed = AtomicInteger()
        val focus: (() -> Unit) -> AutoCloseable? =
            if (realFocus) SpeechAudio(context)::focus else { _ -> AutoCloseable {} }
        val service =
            ReadAloudService(model, {
                object : SpeechEngine {
                    override fun generate(
                        text: String,
                        keepGoing: () -> Boolean,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        consume(floatArrayOf(0.1f), 16_000)
                        while (keepGoing()) Thread.sleep(5)
                    }

                    override fun close() {
                        closed.incrementAndGet()
                    }
                }
            }, focus, {
                object : SpeechSink {
                    override fun write(
                        samples: FloatArray,
                        keepGoing: () -> Boolean,
                    ) = Unit

                    override fun finish(keepGoing: () -> Boolean) = Unit

                    override fun interrupt() = Unit

                    override fun close() = Unit
                }
            })
        try {
            block(service, closed)
        } finally {
            compose.runOnUiThread(service::close)
            root.deleteRecursively()
        }
    }

    @Test
    fun missingVoiceExplainsExplicitDownloadWithoutAutomaticSpeech() =
        fixture(ready = false) { service, _ ->
            compose.setContent { MaterialTheme { ReadAloudAction("missing", "Synthetic text", service) } }
            compose.onNodeWithTag("chat-read-missing").performClick()
            compose.waitUntil(5000) { service.state.value.phase == ReadAloudPhase.MISSING }
            compose.onNodeWithTag("read-aloud-download").assertExists()
            assertNull(service.model.downloadedBytes.value)
        }

    @Test
    fun explicitStopReplayAndSwitchHaveOneActiveMessage() =
        fixture { service, closed ->
            compose.setContent {
                MaterialTheme {
                    Column {
                        ReadAloudAction("one", "Synthetic one", service)
                        ReadAloudAction("two", "Synthetic two", service)
                    }
                }
            }
            compose.onNodeWithTag("chat-read-one").performClick()
            compose.waitUntil(5000) { service.state.value.phase == ReadAloudPhase.SPEAKING }
            compose.onNodeWithTag("chat-read-one").performClick()
            compose.waitUntil(5000) { closed.get() == 1 && service.state.value.phase == ReadAloudPhase.IDLE }
            compose.onNodeWithTag("chat-read-one").performClick()
            compose.waitUntil(5000) { service.state.value.phase == ReadAloudPhase.SPEAKING }
            compose.onNodeWithTag("chat-read-two").performClick()
            compose.waitUntil(5000) {
                service.state.value.messageId == "two" && service.state.value.phase == ReadAloudPhase.SPEAKING
            }
            assertEquals(2, closed.get())
        }

    @Test
    fun backgroundAndSessionChangeStopWithoutAutomaticResume() =
        fixture { service, closed ->
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this)
                }
            val session = mutableStateOf("first")
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        ReadAloudLifecycle(service, session.value)
                        ReadAloudAction("item", "Synthetic text", service)
                    }
                }
            }
            compose.onNodeWithTag("chat-read-item").performClick()
            compose.waitUntil(5000) { service.state.value.phase == ReadAloudPhase.SPEAKING }
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            compose.waitUntil(5000) { closed.get() == 1 && service.state.value.phase == ReadAloudPhase.IDLE }
            compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle()
            assertEquals(ReadAloudPhase.IDLE, service.state.value.phase)
            compose.onNodeWithTag("chat-read-item").performClick()
            compose.waitUntil(5000) { service.state.value.phase == ReadAloudPhase.SPEAKING }
            compose.runOnUiThread { session.value = "second" }
            compose.waitUntil(5000) { closed.get() == 2 && service.state.value.phase == ReadAloudPhase.IDLE }
        }

    @Test
    fun actualAudioFocusLossStopsSilentInjectedSpeech() =
        fixture(realFocus = true) { service, closed ->
            check(
                android.os.Build.MODEL
                    .contains("sdk_gphone"),
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(AudioManager::class.java)
            compose.setContent { MaterialTheme { ReadAloudAction("focus", "Synthetic", service) } }
            compose.onNodeWithTag("chat-read-focus").performClick()
            compose.waitUntil(5000) { service.state.value.phase == ReadAloudPhase.SPEAKING }
            val competing =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setOnAudioFocusChangeListener {}
                    .build()
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competing))
                compose.waitUntil(5000) { closed.get() == 1 && service.state.value.phase == ReadAloudPhase.IDLE }
            } finally {
                manager.abandonAudioFocusRequest(competing)
            }
        }

    @Test fun readEntryIsOnlyOnCompletedAssistantActions() {
        compose.setContent {
            MaterialTheme {
                Column {
                    MessageRow(MessageUi("complete", "assistant", "Synthetic completed reply"))
                    MessageRow(MessageUi("live", "assistant", "Synthetic streaming"), readAloud = false)
                    MessageRow(MessageUi("user", "user", "Synthetic user"))
                    MessageRow(MessageUi("tool", "tool", "Synthetic tool result"))
                }
            }
        }
        listOf("complete", "live", "user", "tool").forEach {
            compose.onNodeWithTag("chat-message-toggle-$it").performClick()
        }
        compose.onNodeWithTag("chat-read-complete").assertExists()
        compose.onNodeWithTag("chat-read-live").assertDoesNotExist()
        compose.onNodeWithTag("chat-read-user").assertDoesNotExist()
        compose.onNodeWithTag("chat-read-tool").assertDoesNotExist()
    }
}
