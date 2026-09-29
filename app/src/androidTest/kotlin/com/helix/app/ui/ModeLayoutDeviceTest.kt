package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.core.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModeLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localCommandsRemainAvailableWithUndeliverableAttachments() {
        val input = mutableStateOf("/plan ")
        var changes = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    input.value,
                    { input.value = it },
                    false,
                    true,
                    ComposerActions(
                        onFile = {},
                        onVoice = {},
                        onSend = { error("Must not send attachments") },
                        onStop = {},
                    ),
                    onMode = {
                        changes++
                        true
                    },
                    availability = ComposerAvailability(delivery = false, localCommands = true),
                )
            }
        }
        compose.onNodeWithTag("chat-current-mode").assertIsDisplayed()
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(1, changes)
            input.value = "/act task"
        }
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { input.value = "/help " }
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-command-notice").assertIsDisplayed()
    }

    @Suppress("LongMethod") // One staged-command lifecycle including running and argument rejection.
    @Test
    fun commandsStageBeforeSendAndRunningTurnsKeepTheirMode() {
        val input = mutableStateOf("")
        val active = mutableStateOf(false)
        var mode = AgentMode.ACT
        var sends = 0
        var compactions = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        ConversationComposer(
                            input.value,
                            { input.value = it },
                            active.value,
                            false,
                            ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                            onMode = {
                                mode = it
                                true
                            },
                            onCompact = { compactions++ },
                            canCompact = true,
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("chat-mode-menu").assertDoesNotExist()
        AgentMode.entries.forEach { requested ->
            val before = mode
            compose.runOnIdle { input.value = "/${requested.name.lowercase()}" }
            compose.onNodeWithTag("composer-suggestion-slash:${requested.name.lowercase()}").performClick()
            compose.runOnIdle {
                assertEquals(before, mode)
                assertEquals("/${requested.name.lowercase()} ", input.value)
                assertEquals(0, sends)
            }
            compose.onNodeWithTag("chat-send").performClick()
            compose.runOnIdle {
                assertEquals(requested, mode)
                assertEquals("", input.value)
            }
        }
        compose.runOnIdle { input.value = "/compact" }
        compose.onNodeWithTag("composer-suggestion-slash:compact").performClick()
        compose.runOnIdle { assertEquals(0, compactions) }
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(1, compactions)
            active.value = true
            input.value = "/act "
        }
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-command-notice").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(AgentMode.GOAL, mode)
            assertEquals("/act ", input.value)
        }
        compose.runOnIdle {
            active.value = false
            input.value = "/act keep this task"
        }
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals("keep this task", input.value)
            assertEquals(1, sends)
        }
        compose.runOnIdle { input.value = "/help " }
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-command-notice").assertIsDisplayed()
        compose.runOnIdle { input.value = "/clear " }
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals("", input.value)
            assertEquals(1, sends)
        }
        compose.runOnIdle { input.value = "normal text" }
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle { assertEquals(2, sends) }
    }
}
