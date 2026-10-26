package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.HelixTheme
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.internal.LineStore
import com.helix.app.voice.CloudAsrConnection
import com.helix.app.voice.CloudAsrFactory
import com.helix.app.voice.PcmCapture
import com.helix.app.voice.SpeechConsentStore
import com.helix.app.voice.VoiceCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic PCM/transport and in-memory consent only; no microphone, account or network. */
class VoiceConsentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private var generation by mutableStateOf(0)
    private val opened = AtomicInteger()
    private var draft = "original draft"
    private var notice = ""

    @Composable
    @Suppress("FunctionName")
    private fun Fixture(consent: SpeechConsentStore) {
        androidx.compose.runtime.key(generation) {
            HelixTheme {
                if (visible) {
                    InlineVoiceInput(
                        onDismiss = { visible = false },
                        onAccept = { draft = it },
                        onError = { notice = it },
                        suppliedConsent = consent,
                        permissionChecker = { true },
                        captureFactory = { context, scope ->
                            VoiceCapture(
                                context,
                                scope,
                                CloudAsrFactory("https://speech.example.test/asr") {
                                    opened.incrementAndGet()
                                    object : CloudAsrConnection {
                                        override fun send(samples: FloatArray) = "synthetic"

                                        override fun finish() = "synthetic final"

                                        override fun close() = Unit
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
            }
        }
    }

    @Test fun firstUseDoesNotOpenTransportUntilConsentAndNextCaptureDoesNotAskAgain() {
        val consent = SpeechConsentStore(InMemoryLineStore())
        compose.setContent { Fixture(consent) }
        compose.onNodeWithTag("voice-upload-consent").assertExists()
        assertEquals(0, opened.get())
        compose.onNodeWithTag("voice-upload-consent").performClick()
        compose.waitUntil(5000) { opened.get() == 1 }
        compose.onNodeWithTag("voice-cancel").performClick()
        compose.runOnIdle {
            generation++
            visible = true
        }
        compose.waitUntil(5000) { opened.get() == 2 }
        compose.onNodeWithTag("voice-upload-consent").assertDoesNotExist()
        compose.onNodeWithTag("voice-cancel").performClick()
        assertEquals("original draft", draft)
    }

    @Test fun failedConsentPersistenceDoesNotOpenTransportOrOverwriteDraft() {
        val consent =
            SpeechConsentStore(
                object : LineStore {
                    override fun lines(key: String): List<String> = emptyList()

                    override fun setLines(
                        key: String,
                        lines: List<String>,
                    ) {
                        error("synthetic failure")
                    }
                },
            )
        compose.setContent { Fixture(consent) }
        compose.onNodeWithTag("voice-upload-consent").performClick()
        compose.waitUntil(5000) { !visible }
        assertEquals(0, opened.get())
        assertEquals("original draft", draft)
        assertTrue(notice.isNotBlank())
        assertFalse(notice.contains("synthetic failure"))
    }
}
