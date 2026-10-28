package com.helix.app.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixTheme
import com.helix.app.voice.VoiceCapture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Inert UI fixtures: no microphone, permissions or transport. */
class VoiceInputControlsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finishHasImmediateProcessingFeedbackAndKeepsCancel() {
        val state = mutableStateOf(VoiceCapture.State(VoiceCapture.Phase.LISTENING))
        var cancelled = 0
        compose.setContent {
            HelixTheme {
                Surface {
                    InlineVoiceControls(
                        state.value,
                        true,
                        true,
                        false,
                        null,
                        true,
                        onCancel = { cancelled++ },
                        onConsent = {},
                        onGrant = {},
                        onFinish = { state.value = VoiceCapture.State(VoiceCapture.Phase.FINISHING) },
                        onRetry = {},
                    )
                }
            }
        }
        savePanel("voice-listening")
        compose.onNodeWithTag("voice-finish").performClick()
        compose.onNodeWithTag("voice-finish").assertDoesNotExist()
        savePanel("voice-processing")
        compose.onNodeWithTag("voice-cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test fun failedAndEmptyResultsOfferRetryInTheSamePanel() {
        val state =
            mutableStateOf(VoiceCapture.State(VoiceCapture.Phase.FAILED, failure = VoiceCapture.Failure.CONNECTION))
        var retries = 0
        compose.setContent {
            HelixTheme {
                Surface {
                    InlineVoiceControls(
                        state.value,
                        true,
                        true,
                        false,
                        null,
                        false,
                        onCancel = {},
                        onConsent = {},
                        onGrant = {},
                        onFinish = {},
                        onRetry = { retries++ },
                    )
                }
            }
        }
        savePanel("voice-error")
        compose.onNodeWithTag("voice-retry").performClick()
        compose.runOnIdle { state.value = VoiceCapture.State(VoiceCapture.Phase.REVIEW) }
        compose.onNodeWithTag("voice-retry").performClick()
        assertEquals(2, retries)
        compose.onNodeWithTag("voice-cancel").assertExists()
    }

    private fun savePanel(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onNodeWithTag("voice-inline").captureToImage().asAndroidBitmap()
        java.io.File(context.cacheDir, "$name.png").outputStream().use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
