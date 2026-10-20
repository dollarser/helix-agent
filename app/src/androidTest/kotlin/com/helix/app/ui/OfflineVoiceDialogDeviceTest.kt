package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.voice.PcmCapture
import com.helix.app.voice.StreamingAsr
import com.helix.app.voice.StreamingAsrFactory
import com.helix.app.voice.VoiceCapture
import com.helix.app.voice.VoiceInputMapper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Granted permission plus injected PCM: this suite never records environmental audio. */
class OfflineVoiceDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun grantPermissionForInjectedPcm() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        check(
            android.os.Build.MODEL
                .contains("sdk_gphone"),
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            android.Manifest.permission.RECORD_AUDIO,
        )
    }

    private fun hasNode(tag: String) =
        compose
            .onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(tag),
            ).fetchSemanticsNodes()
            .isNotEmpty()

    private val closed = AtomicBoolean(false)

    private fun asr() =
        StreamingAsrFactory {
            object : StreamingAsr {
                override fun accept(samples: FloatArray) = "实时中文"

                override fun finish() = "完整中文"

                override fun close() {
                    closed.set(true)
                }
            }
        }

    private fun source() =
        object : PcmCapture {
            override fun read(samples: ShortArray): Int {
                Thread.sleep(20)
                return samples.size
            }

            override fun stop() = Unit

            override fun close() = Unit
        }

    @Test fun streamingReviewEditsAndAddsToExistingDraftWithoutSending() {
        var draft = "原草稿"
        val open = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open.value) {
                    OfflineVoiceDialog(
                        { open.value = false },
                        { draft = VoiceInputMapper.appendDraft(draft, it) },
                        { context, scope -> VoiceCapture(context, scope, asr()) { source() } },
                    )
                }
            }
        }
        compose.waitUntil(5000) { hasNode("voice-partial") }
        compose.onNodeWithTag("voice-partial").assertIsDisplayed()
        compose.onNodeWithTag("voice-finish").performClick()
        compose.waitUntil(5000) { hasNode("voice-review") }
        assertEquals("原草稿", draft)
        compose.onNodeWithTag("voice-review").performTextReplacement("修订中文")
        compose.onNodeWithTag("voice-accept").performClick()
        assertEquals("原草稿 修订中文", draft)
        compose.waitUntil(5000) { closed.get() }
    }

    @Test fun cancelPreservesOriginalDraftAndClosesEngine() {
        var draft = "不能丢失"
        val open = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open.value) {
                    OfflineVoiceDialog(
                        { open.value = false },
                        { draft = it },
                        { context, scope -> VoiceCapture(context, scope, asr()) { source() } },
                    )
                }
            }
        }
        compose.waitUntil(5000) { hasNode("voice-partial") }
        compose.onNodeWithTag("voice-cancel").performClick()
        compose.waitUntil(5000) { closed.get() }
        assertEquals("不能丢失", draft)
    }

    @Test
    fun backgroundStopsCaptureAndCannotAcceptLateText() {
        val owner =
            object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
            }
        compose.runOnUiThread { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        var draft = "后台保留"
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides owner,
            ) {
                MaterialTheme {
                    OfflineVoiceDialog(
                        {},
                        { draft = it },
                        { context, scope -> VoiceCapture(context, scope, asr()) { source() } },
                    )
                }
            }
        }
        compose.waitUntil(5000) { hasNode("voice-partial") }
        compose.runOnUiThread { owner.lifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.waitUntil(5000) { closed.get() }
        compose.onNodeWithTag("voice-accept").assertDoesNotExist()
        assertEquals("后台保留", draft)
    }

    @Test
    fun switchingConversationDisposesCaptureAndPreservesBothDrafts() {
        val session = mutableStateOf("first")
        var first = "会话一草稿"
        var second = "会话二草稿"
        compose.setContent {
            androidx.compose.runtime.key(session.value) {
                val visible = androidx.compose.runtime.remember { mutableStateOf(session.value == "first") }
                MaterialTheme {
                    if (visible.value) {
                        OfflineVoiceDialog(
                            { visible.value = false },
                            { if (session.value == "first") first = it else second = it },
                            { context, scope -> VoiceCapture(context, scope, asr()) { source() } },
                        )
                    }
                }
            }
        }
        compose.waitUntil(5000) { hasNode("voice-partial") }
        compose.runOnUiThread { session.value = "second" }
        compose.waitUntil(5000) { closed.get() }
        compose.onNodeWithTag("voice-accept").assertDoesNotExist()
        assertEquals("会话一草稿", first)
        assertEquals("会话二草稿", second)
    }
}
