package com.helix.app.ui

import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.chat.ChatScreenState
import com.helix.app.chat.MessagePresentation
import com.helix.app.chat.MessageUi
import com.helix.app.chat.TurnUi
import com.helix.app.voice.LocalSpeechModel
import com.helix.app.voice.ReadAloudPhase
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.SpeechAudio
import com.helix.app.voice.SpeechEngine
import com.helix.app.voice.SpeechSink
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        spoken: MutableList<String>? = null,
        accountIdentity: String? = null,
        block: (ReadAloudService, AtomicInteger) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "tts-ui-${UUID.randomUUID()}")
        val data = "synthetic".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val part =
            LocalSpeechModel.Part(
                "model.bin",
                "https://huggingface.co/synthetic",
                data.size.toLong(),
                hash,
            )
        if (ready) {
            File(root, "melo-int8").mkdirs()
            File(root, "melo-int8/model.bin").writeBytes(data)
        }
        val model = LocalSpeechModel(context, root, { error("No automatic network request") }, listOf(part))
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
                        spoken?.add(text)
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
            }, accountIdentity = accountIdentity)
        try {
            block(service, closed)
        } finally {
            compose.runOnUiThread(service::close)
            root.deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod") // One final-answer journey restores the owner's global setting in finally.
    fun automaticSpeechWaitsForPersistedFinalAndStopsWithoutReplaying() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
        val original = app.replyAudioSettings.automatic.value
        val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())
        val screen =
            mutableStateOf(
                ChatScreenState(
                    emptyList(),
                    "auto-session",
                    null,
                    emptyList(),
                    emptyList(),
                    TurnUi("auto-turn", TurnState.RECEIVING_MODEL, "中间过程。", null, false),
                    null,
                    null,
                    null,
                ),
            )
        try {
            runBlocking { app.replyAudioSettings.setAutomatic(true) }
            fixture(spoken = spoken) { reader, closed ->
                compose.setContent {
                    MaterialTheme {
                        ReadAloudLifecycle(reader, screen.value.openSessionId)
                        val frame = visibleReplyFrame(screen.value)
                        ObserveLiveReplyReadAloud(reader, frame)
                        AutomaticReplyAudioControl(
                            reader,
                            frame,
                            app.replyAudioSettings,
                            app.speechConsent,
                            app.account.identityState.value,
                            app.account.signedIn.value,
                        )
                    }
                }
                compose.waitForIdle()
                assertEquals(ReadAloudPhase.IDLE, reader.state.value.phase)
                for (phase in listOf(TurnState.RUNNING_TOOL, TurnState.RECEIVING_MODEL, TurnState.COMPLETED)) {
                    compose.runOnIdle {
                        screen.value = screen.value.copy(activeTurn = screen.value.activeTurn?.copy(state = phase))
                    }
                    compose.waitForIdle()
                    assertEquals(
                        "No progress or missing final may start speech",
                        ReadAloudPhase.IDLE,
                        reader.state.value.phase,
                    )
                }
                compose.runOnIdle {
                    screen.value =
                        screen.value.copy(
                            messages =
                                listOf(
                                    MessageUi(
                                        "progress",
                                        "assistant",
                                        "工具前的过程",
                                        "auto-turn",
                                        presentation = MessagePresentation.PROGRESS,
                                    ),
                                    MessageUi("final", "assistant", "<think>隐藏思考</think>最终结论。", "auto-turn"),
                                ),
                        )
                }
                compose.waitUntil(10_000) { reader.state.value.phase == ReadAloudPhase.SPEAKING }
                assertEquals(listOf("最终结论。"), spoken.toList())
                assertEquals("auto-turn", reader.livePlaybackTurnId)
                compose.onNodeWithTag("chat-auto-read").performClick()
                compose.waitUntil(10_000) { !app.replyAudioSettings.automatic.value && closed.get() > 0 }
                compose.waitUntil(10_000) { reader.state.value.phase == ReadAloudPhase.IDLE }
                compose.onNodeWithTag("chat-auto-read").performClick()
                compose.waitUntil(10_000) { app.replyAudioSettings.automatic.value }
                compose.waitForIdle()
                assertEquals(ReadAloudPhase.IDLE, reader.state.value.phase)
                assertEquals(listOf("最终结论。"), spoken.toList())
            }
        } finally {
            runBlocking { app.replyAudioSettings.setAutomatic(original) }
        }
    }

    @Test
    @Suppress("LongMethod") // One navigation boundary proves historical finals never start or revive audio.
    fun historicalFinalIsSilentAndChangingTurnStopsFinalAudio() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
        val original = app.replyAudioSettings.automatic.value
        val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())
        val screen =
            mutableStateOf(
                ChatScreenState(
                    emptyList(),
                    "auto-session",
                    null,
                    emptyList(),
                    emptyList(),
                    TurnUi("old-turn", TurnState.COMPLETED, null, null, false),
                    null,
                    null,
                    null,
                ),
            )
        try {
            runBlocking { app.replyAudioSettings.setAutomatic(true) }
            fixture(spoken = spoken) { reader, closed ->
                compose.setContent {
                    MaterialTheme {
                        ReadAloudLifecycle(reader, screen.value.openSessionId)
                        val frame = visibleReplyFrame(screen.value)
                        ObserveLiveReplyReadAloud(reader, frame)
                        AutomaticReplyAudioControl(
                            reader,
                            frame,
                            app.replyAudioSettings,
                            app.speechConsent,
                            app.account.identityState.value,
                            app.account.signedIn.value,
                        )
                    }
                }
                compose.waitForIdle()
                assertEquals(ReadAloudPhase.IDLE, reader.state.value.phase)
                assertTrue(spoken.isEmpty())
                compose.runOnIdle {
                    screen.value =
                        screen.value.copy(
                            messages =
                                listOf(
                                    MessageUi("old", "assistant", "旧回答。", "old-turn"),
                                ),
                        )
                }
                compose.waitForIdle()
                assertEquals(ReadAloudPhase.IDLE, reader.state.value.phase)
                assertTrue(spoken.isEmpty())
                compose.runOnIdle {
                    screen.value =
                        screen.value.copy(
                            activeTurn = TurnUi("new-turn", TurnState.RECEIVING_MODEL, "过程。", null, false),
                        )
                }
                compose.waitForIdle()
                compose.runOnIdle {
                    screen.value =
                        screen.value.copy(
                            activeTurn = screen.value.activeTurn?.copy(state = TurnState.COMPLETED),
                            messages = screen.value.messages + MessageUi("new", "assistant", "新结论。", "new-turn"),
                        )
                }
                compose.waitUntil(10_000) { reader.state.value.phase == ReadAloudPhase.SPEAKING }
                assertEquals(listOf("新结论。"), spoken.toList())
                compose.runOnIdle {
                    screen.value =
                        screen.value.copy(
                            activeTurn = TurnUi("next-turn", TurnState.RECEIVING_MODEL, null, null, false),
                        )
                }
                compose.waitUntil(10_000) { closed.get() > 0 && reader.state.value.phase == ReadAloudPhase.IDLE }
                assertFalse(reader.livePlaybackTurnId != null)
                compose.runOnIdle { screen.value = screen.value.copy(openSessionId = "history", activeTurn = null) }
                compose.waitForIdle()
                assertEquals(listOf("新结论。"), spoken.toList())
                compose.runOnIdle {
                    reader.leave()
                    screen.value =
                        screen.value.copy(
                            openSessionId = "auto-session",
                            activeTurn = TurnUi("next-turn", TurnState.COMPLETED, null, null, false),
                            messages = listOf(MessageUi("late", "assistant", "离页后完成。", "next-turn")),
                        )
                }
                compose.waitForIdle()
                assertEquals(ReadAloudPhase.IDLE, reader.state.value.phase)
                assertEquals(listOf("新结论。"), spoken.toList())
            }
        } finally {
            runBlocking { app.replyAudioSettings.setAutomatic(original) }
        }
    }

    @Test fun resourcePanelRemovesOnlyAfterConfirmationWithoutStartingSpeech() =
        fixture { service, closed ->
            compose.setContent { MaterialTheme { OfflineTtsModelPanel(service) } }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("offline-tts-remove").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("offline-tts-remove").performClick()
            assertTrue(kotlinx.coroutines.runBlocking { service.model.ready() != null })
            compose.onNodeWithTag("offline-tts-remove-confirm").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("offline-tts-download").fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(kotlinx.coroutines.runBlocking { service.model.ready() })
            assertEquals(0L, kotlinx.coroutines.runBlocking { service.model.installedBytes() })
            assertEquals(0, closed.get())
            assertNull(service.model.downloadedBytes.value)
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

    @Test fun injectedAccountIdentityChangeStopsPlayback() =
        fixture(accountIdentity = "synthetic-account") { service, closed ->
            val identity = mutableStateOf("synthetic-account")
            compose.setContent {
                MaterialTheme {
                    ReadAloudAction(
                        "identity",
                        "Synthetic reply",
                        service,
                        accountIdentity = identity.value,
                        accountStatus = com.helix.app.account.AccountStatus.SIGNED_IN,
                    )
                }
            }
            compose.onNodeWithTag("chat-read-identity").performClick()
            compose.waitUntil(5000) { service.state.value.phase == ReadAloudPhase.SPEAKING }
            compose.runOnIdle { identity.value = "another-account" }
            compose.waitUntil(5000) { closed.get() == 1 && service.state.value.phase == ReadAloudPhase.IDLE }
            compose.onNodeWithTag("chat-read-identity").performClick()
            compose.waitForIdle()
            assertEquals(ReadAloudPhase.IDLE, service.state.value.phase)
        }

    @Test fun readEntryIsOnlyOnCompletedAssistantActions() =
        fixture { service, _ ->
            compose.setContent {
                MaterialTheme {
                    Column {
                        listOf(
                            MessageUi("complete", "assistant", "Synthetic completed reply"),
                            MessageUi("live", "assistant", "Synthetic streaming"),
                            MessageUi("user", "user", "Synthetic user"),
                            MessageUi("tool", "tool", "Synthetic tool result"),
                        ).forEach { message ->
                            MessageRow(
                                message,
                                readAloud = message.id != "live",
                                readAloudAction = { ReadAloudAction(message.id, message.content, service) },
                            )
                        }
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
