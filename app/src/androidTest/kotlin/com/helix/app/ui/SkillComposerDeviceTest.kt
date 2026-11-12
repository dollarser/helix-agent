package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.input.InputSkillActivation
import com.helix.feature.conversation.presentation.ComposerActions
import com.helix.feature.conversation.presentation.ConversationComposer
import com.helix.feature.conversation.presentation.composer.ComposerSuggestionItem
import com.helix.feature.conversation.presentation.composer.ComposerSuggestionType
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SkillComposerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val first = InputSkillActivation("BUILT_IN", "read", "a".repeat(64), "")
    private val second = InputSkillActivation("USER_IMPORTED", "read", "b".repeat(64), "")
    private val option =
        ComposerSuggestionItem("skill:read", "/read", "Read documents", ComposerSuggestionType.SKILL, "")

    // One scenario verifies both deletion and recreation without losing the remaining skill's identity.
    @Suppress("LongMethod")
    @Test
    fun singleBackspaceRemovesWholeChipAndRestorationPreservesOtherIdentityAndChineseText() {
        val text = mutableStateOf("保留中文")
        val selected = mutableStateOf(listOf(first, second))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                ConversationComposer(
                    text.value,
                    { text.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                    skillActivations = selected.value,
                    onSkillActivationsChange = { selected.value = it },
                )
            }
        }
        compose.onNodeWithTag("chat-input").assertTextContains("read", substring = true)
        compose
            .onNodeWithTag("chat-input")
            .performClick()
            .performTextInputSelection(TextRange(1), relativeToOriginalText = true)
        compose.waitForIdle()
        val selection =
            compose.onNodeWithTag("chat-input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(1), selection)
        key(android.view.KeyEvent.KEYCODE_DEL)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                val node = compose.onNodeWithTag("chat-input").fetchSemanticsNode()
                val afterSelection = node.config[SemanticsProperties.TextSelectionRange]
                putString(
                    "stream",
                    "SKILL_DELETE_AFTER selection=$afterSelection text=${text.value} skills=${selected.value.size}\n",
                )
            },
        )
        compose.runOnIdle {
            assertEquals(listOf(second), selected.value)
            assertEquals("保留中文", text.value)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chat-input").assertTextContains("read", substring = true)
        compose.onNodeWithTag("chat-input").assertTextContains("保留中文", substring = true)
        compose
            .onNodeWithTag("chat-input")
            .performClick()
            .performTextInputSelection(TextRange(1), relativeToOriginalText = true)
        compose.waitForIdle()
        assertEquals(
            TextRange(1),
            compose.onNodeWithTag("chat-input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )
        key(android.view.KeyEvent.KEYCODE_DEL)
        compose.runOnIdle {
            assertEquals(emptyList<InputSkillActivation>(), selected.value)
            assertEquals("保留中文", text.value)
        }
    }

    @Test fun keyboardSelectionCreatesStructuredChipAndPendingValidationFreezesDraft() {
        val text = mutableStateOf("")
        val selected = mutableStateOf(emptyList<InputSkillActivation>())
        val allow = CompletableDeferred<Boolean>()
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    text.value,
                    { text.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    skillActivations = selected.value,
                    skillSuggestions = if (selected.value.isEmpty()) listOf(option) else emptyList(),
                    onSkillActivationsChange = { selected.value = it },
                    onSkillSelect = {
                        if (allow.await()) {
                            selected.value = listOf(first)
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("chat-input").performTextInput("/read")
        compose.onNodeWithTag("chat-input").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("chat-input").assertIsNotEnabled()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("chat-add").assertIsNotEnabled()
        compose.runOnIdle { allow.complete(true) }
        compose.waitUntil(10_000) { selected.value == listOf(first) && text.value.isEmpty() }
        compose.onNodeWithTag("chat-input").assertIsFocused()
        assertEquals(
            TextRange(1),
            compose.onNodeWithTag("chat-input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )
        compose.onNodeWithTag("chat-input").assertTextContains("read", substring = true)
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        compose.onNodeWithTag("chat-input").performTextInput("你好")
        compose.runOnIdle {
            assertEquals("你好", text.value)
            assertEquals(0, sends)
        }
    }

    @Test fun failedSelectionKeepsSlashTextAndNeverActivatesOrSends() {
        val text = mutableStateOf("/read")
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    text.value,
                    { text.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    skillSuggestions = listOf(option),
                    onSkillSelect = { false },
                )
            }
        }
        compose.onNodeWithTag("composer-suggestion-skill:read").performClick()
        compose.onNodeWithTag("chat-command-notice").assertExists()
        compose.runOnIdle {
            assertEquals("/read", text.value)
            assertEquals(0, sends)
        }
    }

    @Test fun ambiguousSlashNameRequiresExplicitChoiceInsteadOfSendingPlainText() {
        var sends = 0
        var selections = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    "/read",
                    {},
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    skillSuggestions = listOf(option, option.copy(id = "skill:other-read")),
                    onSkillSelect = {
                        selections++
                        false
                    },
                )
            }
        }
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("chat-command-notice").assertExists()
        compose.runOnIdle {
            assertEquals(0, sends)
            assertEquals(0, selections)
        }
        compose.onNodeWithTag("composer-suggestion-skill:other-read").performClick()
        compose.runOnIdle { assertEquals(1, selections) }
    }

    @Test fun committedSlashNameAndSpaceCreateChipImmediatelyAndKeepTaskBody() {
        val text = mutableStateOf("")
        val selected = mutableStateOf(emptyList<InputSkillActivation>())
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    text.value,
                    { text.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    skillActivations = selected.value,
                    skillSuggestions = if (selected.value.isEmpty()) listOf(option) else emptyList(),
                    onSkillActivationsChange = { selected.value = it },
                    onSkillSelect = {
                        selected.value = listOf(first)
                        true
                    },
                )
            }
        }
        compose.onNodeWithTag("chat-input").performTextInput("/read 保留正文")
        compose.waitUntil(10_000) { selected.value == listOf(first) && text.value == "保留正文" }
        compose.onNodeWithTag("chat-input").assertTextContains("read", substring = true)
        compose.onNodeWithTag("chat-input").assertTextContains("保留正文", substring = true)
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun unmatchedNamesAndBuiltinCompactWithTaskNeverAutoActivateSkills() {
        val text = mutableStateOf("")
        var selections = 0
        var focus: String? = null
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    text.value,
                    { text.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                    skillSuggestions = listOf(option, option.copy(id = "skill:compact", label = "/compact")),
                    onSkillSelect = {
                        selections++
                        true
                    },
                    canCompact = true,
                    onCompact = { focus = it },
                )
            }
        }
        compose.onNodeWithTag("chat-input").performTextInput("/reader 保留正文")
        compose.runOnIdle {
            assertEquals(0, selections)
            assertEquals("/reader 保留正文", text.value)
        }
        compose.onNodeWithTag("chat-input").performTextClearance()
        compose.onNodeWithTag("chat-input").performTextInput("/compact task")
        compose.runOnIdle { assertEquals(0, selections) }
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals("task", focus)
            assertEquals(0, selections)
        }
    }

    @Test fun compactSkillSelectionNeverExecutesBuiltinCompaction() {
        val text = mutableStateOf("/compact")
        val selected = mutableStateOf(emptyList<InputSkillActivation>())
        val compactSkill = first.copy(name = "compact")
        val compactOption = option.copy(id = "skill:compact", label = "/compact")
        var sends = 0
        var compactions = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    text.value,
                    { text.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    skillActivations = selected.value,
                    skillSuggestions = if (selected.value.isEmpty()) listOf(compactOption) else emptyList(),
                    onSkillActivationsChange = { selected.value = it },
                    onSkillSelect = {
                        selected.value = listOf(compactSkill)
                        true
                    },
                    canCompact = true,
                    onCompact = { compactions++ },
                )
            }
        }
        compose.onNodeWithTag("composer-suggestion-slash:compact").assertExists()
        compose.onNodeWithTag("composer-suggestion-skill:compact").performClick()
        compose.waitUntil(10_000) { selected.value == listOf(compactSkill) && text.value.isEmpty() }
        compose.onNodeWithTag("chat-input").assertTextContains("compact", substring = true)
        compose.onNodeWithTag("chat-input").performTextInput("/compact")
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(listOf(compactSkill), selected.value)
            assertEquals("/compact", text.value)
            assertEquals(1, sends)
            assertEquals(0, compactions)
            selected.value = emptyList()
        }
        compose.onNodeWithTag("chat-send").performClick()
        compose.runOnIdle {
            assertEquals(1, sends)
            assertEquals(1, compactions)
            assertEquals("", text.value)
        }
    }

    @Test fun keyboardHighlightScrollsLongSuggestionListIntoView() {
        val highlighted = mutableStateOf(0)
        val suggestions = (0 until 12).map { option.copy(id = "skill:$it", label = "/skill-$it") }
        compose.setContent {
            MaterialTheme {
                com.helix.feature.conversation.presentation.composer.ComposerAutocompletePopup(
                    suggestions,
                    {},
                    highlightedIndex = highlighted.value,
                )
            }
        }
        compose.runOnIdle { highlighted.value = 11 }
        compose.onNodeWithTag("composer-suggestion-skill:11").assertIsDisplayed()
        compose.runOnIdle { highlighted.value = 0 }
        compose.onNodeWithTag("composer-suggestion-skill:0").assertIsDisplayed()
    }

    private fun key(code: Int) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }
}
