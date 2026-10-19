package com.helix.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.voice.OfflineAsrFactory
import com.helix.app.voice.PcmCapture
import com.helix.app.voice.StreamingAsrFactory
import com.helix.app.voice.VoiceCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Owner-authorized emulator only, with hostmicoff; the runner restores permission state afterwards. */
class OfflineVoicePermissionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun clickSystemPermissionButton(id: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val candidates =
                listOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
                    .flatMap { root?.findAccessibilityNodeInfosByViewId("$it:id/$id").orEmpty() }
            if (candidates.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) return
            SystemClock.sleep(50)
        }
        error("System permission button not found: $id")
    }

    private fun requireDenied() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.RECORD_AUDIO),
        )
    }

    @Test
    fun deniedPermissionNeverCreatesEngineAndPreservesDraft() {
        requireDenied()
        val opened = AtomicBoolean(false)
        var draft = "原有草稿"
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    OfflineVoiceDialog(
                        { visible.value = false },
                        { draft = it },
                        { context, scope ->
                            VoiceCapture(
                                context,
                                scope,
                                StreamingAsrFactory {
                                    opened.set(true)
                                    error("Denied permission must not load a model")
                                },
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("voice-grant").performClick()
        clickSystemPermissionButton("permission_deny_button")
        compose.waitForIdle()
        compose.onNodeWithTag("voice-cancel").performClick()
        assertEquals("原有草稿", draft)
        assertTrue(!opened.get())
    }

    @Test
    fun allowedEmulatorMicrophoneFinishesSilenceAndReleasesRecording() {
        requireDenied()
        val closed = AtomicBoolean(false)
        var draft = "保留草稿"
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    OfflineVoiceDialog(
                        { visible.value = false },
                        { draft = it },
                        { context, scope ->
                            VoiceCapture(context, scope, OfflineAsrFactory(context.assets)) { interrupted ->
                                val microphone =
                                    com.helix.app.voice
                                        .MicrophoneCapture(context, interrupted)
                                object : PcmCapture {
                                    override fun read(samples: ShortArray) = microphone.read(samples)

                                    override fun stop() = microphone.stop()

                                    override fun close() {
                                        microphone.close()
                                        closed.set(true)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("voice-grant").performClick()
        clickSystemPermissionButton("permission_allow_foreground_only_button")
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("voice-finish")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voice-finish").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("voice-review")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voice-accept").assertIsNotEnabled()
        compose.waitUntil(5000) { closed.get() }
        compose.onNodeWithTag("voice-cancel").performClick()
        assertEquals("保留草稿", draft)
    }

    @Test
    fun audioFocusLossStopsRecorderWithoutAddingDraft() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(instrumentation.targetContext, Manifest.permission.RECORD_AUDIO),
        )
        val closed = AtomicBoolean(false)
        var draft = "焦点保留草稿"
        var capture: VoiceCapture? = null
        compose.setContent {
            MaterialTheme {
                OfflineVoiceDialog({}, { draft = it }, { context, scope ->
                    VoiceCapture(context, scope, OfflineAsrFactory(context.assets)) { interrupted ->
                        val microphone =
                            com.helix.app.voice
                                .MicrophoneCapture(context, interrupted)
                        object : PcmCapture {
                            override fun read(samples: ShortArray) = microphone.read(samples)

                            override fun stop() = microphone.stop()

                            override fun close() {
                                microphone.close()
                                closed.set(true)
                            }
                        }
                    }.also { capture = it }
                })
            }
        }
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("voice-finish")).fetchSemanticsNodes().isNotEmpty() }
        val manager = instrumentation.targetContext.getSystemService(android.media.AudioManager::class.java)
        val competing =
            android.media.AudioFocusRequest
                .Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build(),
                ).setOnAudioFocusChangeListener {}
                .build()
        try {
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competing))
            compose.waitUntil(5000) { capture?.state?.value?.failure == VoiceCapture.Failure.INTERRUPTED }
            compose.waitUntil(5000) { closed.get() }
            compose.onNodeWithTag("voice-accept").assertDoesNotExist()
            assertEquals("焦点保留草稿", draft)
        } finally {
            manager.abandonAudioFocusRequest(competing)
        }
    }
}
