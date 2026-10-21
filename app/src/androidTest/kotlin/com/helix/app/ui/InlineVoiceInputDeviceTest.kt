package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.HelixTheme
import com.helix.app.voice.PcmCapture
import com.helix.app.voice.StreamingAsr
import com.helix.app.voice.StreamingAsrFactory
import com.helix.app.voice.VoiceCapture
import com.helix.app.voice.VoiceInputMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Injected PCM and permission observation only: no OS grant, microphone or network use. */
class InlineVoiceInputDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val closed = AtomicBoolean(false)
    private var draft by mutableStateOf("原草稿")
    private var preview by mutableStateOf<String?>(null)
    private var open by mutableStateOf(true)
    private var session by mutableStateOf("first")
    private var sent = 0

    @Before
    fun requireOwnedTemporaryPackage() {
        check(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(
                ".asrvalidation",
            ),
        )
    }

    @Composable
    @Suppress("FunctionName")
    private fun Fixture(
        permission: Boolean = true,
        lifecycle: androidx.lifecycle.LifecycleOwner? = null,
    ) {
        val content: @Composable () -> Unit = {
            androidx.compose.runtime.key(session) {
                val base = androidx.compose.runtime.remember { draft }
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
                                    permissionChecker = { permission },
                                    captureFactory = { context, scope ->
                                        VoiceCapture(
                                            context,
                                            scope,
                                            StreamingAsrFactory {
                                                object : StreamingAsr {
                                                    override fun accept(samples: FloatArray) = "实时中文"

                                                    override fun finish() = "完整中文"

                                                    override fun close() {
                                                        closed.set(true)
                                                    }
                                                }
                                            },
                                        ) {
                                            object : PcmCapture {
                                                override fun read(samples: ShortArray): Int {
                                                    Thread.sleep(20)
                                                    return samples.size
                                                }

                                                override fun stop() = Unit

                                                override fun close() = Unit
                                            }
                                        }
                                    },
                                )
                            }
                        },
                        actions = ComposerActions(onFile = {}, onVoice = {}, onSend = { sent++ }, onStop = {}),
                    )
                }
            }
        }
        if (lifecycle == null) {
            content()
        } else {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycle,
                content = content,
            )
        }
    }

    @Test
    fun partialUsesOriginalComposerAndFinalBecomesEditableWithoutSending() {
        compose.setContent { Fixture() }
        compose.waitUntil(5000) { preview == "原草稿 实时中文" }
        compose.onNodeWithTag("chat-input").assertTextEquals("原草稿 实时中文")
        compose.onNodeWithTag("chat-input").assertIsNotEnabled()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        assertEquals("原草稿", draft)
        compose.onNodeWithTag("voice-finish").performClick()
        compose.waitUntil(5000) { !open && closed.get() }
        compose.onNodeWithTag("voice-review").assertDoesNotExist()
        compose.onNodeWithTag("chat-input").assertTextEquals("原草稿 完整中文")
        compose.onNodeWithTag("chat-input").performTextReplacement("修订中文")
        assertEquals("修订中文", draft)
        assertEquals(0, sent)
        compose.onNodeWithTag("chat-send").performClick()
        assertEquals(1, sent)
    }

    @Test
    fun cancelDiscardsTransientPartialAndNeverChangesOriginalDraft() {
        compose.setContent { Fixture() }
        compose.waitUntil(5000) { preview != null }
        compose.onNodeWithTag("voice-cancel").performClick()
        compose.waitUntil(5000) { closed.get() }
        compose.onNodeWithTag("chat-input").assertTextEquals("原草稿")
        assertEquals("原草稿", draft)
        assertEquals(0, sent)
    }

    @Test
    fun switchingSessionDisposesOriginalCaptureWithoutLateDraftOverwrite() {
        compose.setContent { Fixture() }
        compose.waitUntil(5000) { preview != null }
        compose.runOnUiThread {
            session = "second"
            draft = "另一会话草稿"
            preview = null
            open = false
        }
        compose.waitUntil(5000) { closed.get() }
        compose.onNodeWithTag("chat-input").assertTextEquals("另一会话草稿")
        assertEquals(0, sent)
    }

    @Test
    fun backgroundInterruptDisposesCaptureAndRestoresEditableDraft() {
        val owner =
            object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
            }
        compose.runOnUiThread { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent { Fixture(lifecycle = owner) }
        compose.waitUntil(5000) { preview != null }
        compose.runOnUiThread { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.waitUntil(5000) { !open && closed.get() }
        compose.onNodeWithTag("chat-input").assertTextEquals("原草稿")
        assertEquals(0, sent)
    }

    @Test
    fun missingPermissionLeavesEngineUnopenedAndCancelKeepsDraft() {
        compose.setContent { Fixture(permission = false) }
        compose.onNodeWithTag("voice-grant").assertExists()
        compose.onNodeWithTag("voice-finish").assertDoesNotExist()
        compose.onNodeWithTag("voice-cancel").performClick()
        assertEquals("原草稿", draft)
        assertTrue(!closed.get())
        assertEquals(0, sent)
    }
}
