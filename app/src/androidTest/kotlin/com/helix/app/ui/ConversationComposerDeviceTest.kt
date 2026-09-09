package com.helix.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ConversationComposerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowLargeFontKeepsEditingAndStopAccessible() {
        val input = mutableStateOf("")
        val sending = mutableStateOf(false)
        val attachments = mutableStateOf(false)
        var sends = 0
        var stops = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp)) {
                        ConversationComposer(
                            input.value,
                            { input.value = it },
                            sending.value,
                            attachments.value,
                            ComposerActions({}, {}, { sends++ }, { stops++ }),
                        )
                    }
                }
            }
        }
        assertFits("chat-input", "chat-attach", "chat-voice", "chat-send")
        val field = compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot()
        assertTrue("Editing needs the available width", field.right - field.left >= 100.dp)
        val attach = compose.onNodeWithTag("chat-attach").getUnclippedBoundsInRoot()
        assertTrue("Attachment plus stays inside the input", attach.left >= field.left && attach.right <= field.right)
        val voice = compose.onNodeWithTag("chat-voice").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("chat-send").getUnclippedBoundsInRoot()
        val mode = compose.onNodeWithTag("chat-mode-menu").getUnclippedBoundsInRoot()
        val reasoning = compose.onNodeWithTag("chat-reasoning-menu").getUnclippedBoundsInRoot()
        assertTrue("Voice and send flank the input", voice.right <= field.left && send.left >= field.right)
        assertTrue("Toolbar stays above editing", mode.bottom <= field.top && reasoning.bottom <= field.top)
        assertTrue("Reasoning stays on the right", reasoning.left >= mode.right)
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { attachments.value = true }
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        compose.runOnIdle { attachments.value = false }
        compose.onNodeWithTag("chat-input").performTextInput("Draft")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(1, sends)
            sending.value = true
        }
        assertFits("chat-stop")
        listOf("chat-input", "chat-attach", "chat-voice").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        compose.onNodeWithTag("chat-send").assertDoesNotExist()
        compose.onNodeWithTag("chat-stop").performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals("Draft", input.value)
        }
    }

    @Test fun englishGoalActionsHaveRoomAtDoubleFontSize() {
        compose.setContent {
            val context = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.ENGLISH) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(config),
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(Modifier.width(320.dp)) {
                        ConversationComposer("", {}, false, false, ComposerActions({}, {}, {}, {}), goalMode = true)
                    }
                }
            }
        }
        assertFits("chat-input", "chat-attach", "chat-voice", "chat-send")
        val attach = compose.onNodeWithTag("chat-attach").getUnclippedBoundsInRoot()
        assertTrue("Attachment label should fit one line", attach.bottom - attach.top < 72.dp)
        compose.onNodeWithTag("chat-send").assertIsEnabled()
    }

    @Test fun reasoningSelectionIsExplicitAndLockedDuringGeneration() {
        val reasoning = mutableStateOf(com.helix.core.model.ReasoningEffort.OFF)
        val sending = mutableStateOf(false)
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    "draft",
                    {},
                    sending.value,
                    false,
                    ComposerActions({}, {}, { sends++ }, {}),
                    reasoning = reasoning.value,
                    reasoningSupported = true,
                    onReasoning = { reasoning.value = it },
                )
            }
        }
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.onNodeWithTag("chat-reasoning-medium").performClick()
        compose.runOnIdle {
            assertEquals(com.helix.core.model.ReasoningEffort.MEDIUM, reasoning.value)
            assertEquals(0, sends)
        }
        compose.onNodeWithTag("chat-reasoning-menu").performClick()
        compose.runOnIdle { sending.value = true }
        compose.onNodeWithTag("chat-reasoning-menu").assertIsNotEnabled()
        compose.onNodeWithTag("chat-mode-menu").assertIsNotEnabled()
        compose.onNodeWithTag("chat-reasoning-medium").assertDoesNotExist()
    }

    @Test fun optionsRemainInOneScrollableRowOnANarrowScreen() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp)) {
                        ConversationComposer(
                            "Draft",
                            {},
                            false,
                            false,
                            ComposerActions({}, {}, {}, {}),
                            modelSelector = {
                                ComposerModelMenu(
                                    emptyList(),
                                    null,
                                    "A long model name",
                                    true,
                                    { _, _ -> },
                                )
                            },
                        )
                    }
                }
            }
        }
        assertFits("chat-input", "chat-attach", "chat-send")
        val modeBefore = compose.onNodeWithTag("chat-mode-menu").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("chat-options-row").performTouchInput { swipeLeft() }
        val modeAfter = compose.onNodeWithTag("chat-mode-menu").getUnclippedBoundsInRoot()
        assertTrue("Horizontal gesture reveals offscreen options", modeAfter.left < modeBefore.left)
        compose.onNodeWithTag("chat-reasoning-menu").performScrollTo().assertIsDisplayed()
        val model = compose.onNodeWithTag("chat-model-menu").getUnclippedBoundsInRoot()
        val reasoning = compose.onNodeWithTag("chat-reasoning-menu").getUnclippedBoundsInRoot()
        assertTrue(model.right <= reasoning.left)
        assertEquals("Options remain on the same row", model.top, reasoning.top)
        compose.onNodeWithTag("chat-copy-input").performScrollTo().assertIsDisplayed()
        val copy = compose.onNodeWithTag("chat-copy-input").getUnclippedBoundsInRoot()
        val centerOffset = ((reasoning.top + reasoning.bottom) - (copy.top + copy.bottom)) / 2
        assertTrue("Additional options share the row center", centerOffset >= (-1).dp && centerOffset <= 1.dp)
        compose.onNodeWithTag("chat-mode-menu").performScrollTo().assertIsDisplayed()
        assertEquals(modeBefore.top, compose.onNodeWithTag("chat-mode-menu").getUnclippedBoundsInRoot().top)
    }

    private fun assertFits(vararg tags: String) {
        val area = compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot()
        tags.forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag must be contained", bounds.left >= area.left && bounds.right <= area.right)
            assertTrue("$tag must fit vertically", bounds.top >= area.top && bounds.bottom <= area.bottom)
        }
    }
}
