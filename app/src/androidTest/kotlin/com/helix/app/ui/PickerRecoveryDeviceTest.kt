package com.helix.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.helix.app.chat.ChatScreenState
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.runcontrol.TurnBudgetBounds
import com.helix.core.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PickerRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()
    private var request = 0
    private var launchFailure: RuntimeException? = null
    private val registry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                code: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                request = code
                launchFailure?.let { throw it }
            }
        }
    private val owner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }

    @Suppress("LongMethod") // One result-registry restoration and cross-session rejection journey.
    @Test
    fun voiceAndFileResultsSurviveRecreationButNeverCrossSessions() {
        val session = mutableStateOf("first")
        val text = mutableStateOf("draft")
        val attachments = mutableListOf<String>()
        var sends = 0
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    ConversationSection(
                        ChatScreenState(
                            emptyList(),
                            session.value,
                            null,
                            emptyList(),
                            emptyList(),
                            null,
                            null,
                            null,
                            null,
                        ),
                        RunControlConfig(AgentMode.ACT, false, TurnBudgetBounds.DEFAULT),
                        text.value,
                        { text.value = it },
                        emptyList(),
                        ConversationIntents(
                            onSend = { sends++ },
                            onStop = {},
                            onDismissBlocked = {},
                            onApproveApproval = {},
                            onDenyApproval = {},
                            onStageAttachment = { attachments += it },
                            onRemoveAttachment = {},
                            onSetMode = {},
                            onSetChatTools = {},
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag("chat-voice").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.dispatchResult(request, Activity.RESULT_OK, speech("recognized")) }
        compose.runOnIdle { assertEquals("draft recognized", text.value) }
        compose.onNodeWithTag("chat-voice").performClick()
        compose.runOnIdle { session.value = "second" }
        compose.runOnIdle { registry.dispatchResult(request, Activity.RESULT_OK, speech("wrong-session")) }
        compose.runOnIdle {
            assertEquals("draft recognized", text.value)
            assertEquals(0, sends)
        }
        launchFailure = android.content.ActivityNotFoundException()
        pickFile()
        compose.onNodeWithTag("chat-voice-notice").assertExists()
        compose.runOnIdle {
            registry.dispatchResult(request, Activity.RESULT_OK, Intent().setData(Uri.parse("content://fixture/stale")))
            assertEquals(0, attachments.size)
            assertEquals("draft recognized", text.value)
        }
        launchFailure = SecurityException()
        compose.onNodeWithTag("chat-add").performClick()
        compose.onNodeWithTag("composer-add-photo").performClick()
        compose.onNodeWithTag("chat-voice-notice").assertExists()
        launchFailure = null
        pickFile()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            registry.dispatchResult(request, Activity.RESULT_OK, Intent().setData(Uri.parse("content://fixture/first")))
        }
        compose.runOnIdle { assertEquals(listOf("content://fixture/first"), attachments) }
        pickFile()
        compose.runOnIdle { session.value = "third" }
        compose.runOnIdle {
            registry.dispatchResult(request, Activity.RESULT_OK, Intent().setData(Uri.parse("content://fixture/other")))
        }
        compose.runOnIdle { assertEquals(1, attachments.size) }
        compose.onNodeWithTag("chat-add").performClick()
        compose.onNodeWithTag("composer-add-photo").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            registry.dispatchResult(request, Activity.RESULT_OK, Intent().setData(Uri.parse("content://fixture/photo")))
        }
        compose.runOnIdle { assertEquals(2, attachments.size) }
        val previousRequest = request
        compose.onNodeWithTag("chat-add").performClick()
        compose.onNodeWithTag("composer-add-camera").performClick()
        compose.waitUntil(10_000) { request != previousRequest }
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.dispatchResult(request, Activity.RESULT_OK, Intent()) }
        compose.runOnIdle {
            assertEquals(3, attachments.size)
            assertEquals(0, sends)
        }
    }

    private fun pickFile() {
        compose.onNodeWithTag("chat-add").performClick()
        compose.onNodeWithTag("composer-add-file").performClick()
    }

    private fun speech(text: String) =
        Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
}

/** Provides package-manager discovery only; the test registry supplies results without recording audio. */
class FixtureSpeechActivity : Activity()
