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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.runcontrol.TurnBudgetBounds
import com.helix.core.model.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModeLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val config = mutableStateOf(RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT))
    private val active = mutableStateOf(false)

    @Test fun narrowLargeFontKeepsEveryModeVisibleAndPreservesRunningLock() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp)) { ModeControlSection(config.value, active.value, intents()) }
                }
            }
        }
        val area = compose.onNodeWithTag("chat-mode-control").getUnclippedBoundsInRoot()
        AgentMode.entries.forEach { mode ->
            val node = compose.onNodeWithTag("chat-mode-${mode.name.lowercase()}")
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$mode must fit the narrow viewport", bounds.left >= area.left && bounds.right <= area.right)
            assertTrue("$mode must keep a usable touch target", bounds.bottom - bounds.top >= 48.dp)
        }
        compose.onNodeWithTag("chat-mode-goal").performClick()
        compose.runOnIdle { assertEquals(AgentMode.GOAL, config.value.mode) }
        compose.onNodeWithTag("chat-mode-goal").assertIsSelected()
        compose.onNodeWithTag("chat-mode-chat").assertIsNotSelected()
        compose.runOnIdle { active.value = true }
        AgentMode.entries.forEach { mode ->
            compose.onNodeWithTag("chat-mode-${mode.name.lowercase()}").assertIsNotEnabled()
        }
        compose.runOnIdle { assertEquals(AgentMode.GOAL, config.value.mode) }
    }

    private fun intents() =
        ConversationIntents(
            onBack = {},
            onSend = {},
            onStop = {},
            onRetry = {},
            onDismissBlocked = {},
            onApproveApproval = {},
            onDenyApproval = {},
            onStageAttachment = {},
            onRemoveAttachment = {},
            onBindProvider = {},
            onSetMode = { config.value = config.value.copy(mode = it) },
            onSetChatTools = { config.value = config.value.copy(chatToolsEnabled = it) },
        )
}
