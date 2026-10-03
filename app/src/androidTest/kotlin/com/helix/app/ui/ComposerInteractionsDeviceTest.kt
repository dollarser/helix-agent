package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.helix.core.model.SessionPermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposerInteractionsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slashCompletionPlacesCaretAfterCommandAndAcceptsFurtherTyping() {
        val input = mutableStateOf("")
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    input.value,
                    { input.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                )
            }
        }
        compose.onNodeWithTag("chat-input").performTextInput("/")
        compose.onNodeWithTag("composer-suggestion-slash:plan").performClick()
        compose.onNodeWithTag("chat-input").assertTextEquals("/plan ")
        assertEquals(
            TextRange(6),
            compose
                .onNodeWithTag("chat-input")
                .fetchSemanticsNode()
                .config[SemanticsProperties.TextSelectionRange],
        )
        compose.onNodeWithTag("chat-input").performTextInput("next task")
        compose.onNodeWithTag("chat-input").assertTextEquals("/plan next task")
        assertEquals(0, sends)
    }

    @Test fun noModelKeepsDraftAndAttachmentsUntilAnExplicitSendAfterSelection() {
        val input = mutableStateOf("kept draft")
        val selected = mutableStateOf(false)
        var sends = 0
        var pickerRequests = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    input.value,
                    { input.value = it },
                    false,
                    true,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    onChooseModel = { pickerRequests++ },
                    availability = ComposerAvailability(modelSelected = selected.value),
                )
            }
        }
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("chat-input").assertIsEnabled().assertTextEquals("kept draft")
        compose.onNodeWithTag("chat-select-model-reminder").performClick()
        assertEquals(1, pickerRequests)
        assertEquals(0, sends)
        compose.runOnIdle { selected.value = true }
        compose.onNodeWithTag("chat-select-model-reminder").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        assertEquals(0, sends)
        compose.onNodeWithTag("chat-send").performClick()
        assertEquals(1, sends)
    }

    @Test fun clearIsStillLocalAndModeWithTaskCannotSendWithoutModel() {
        val input = mutableStateOf("/act task")
        var sends = 0
        var changes = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    input.value,
                    { input.value = it },
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    onMode = {
                        changes++
                        true
                    },
                    availability = ComposerAvailability(modelSelected = false),
                )
            }
        }
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.runOnIdle { input.value = "/clear" }
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        assertEquals(
            "",
            compose
                .onNodeWithTag("chat-input")
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
        assertEquals(0, sends)
        assertEquals(0, changes)
    }

    @Test fun permissionLivesBottomLeftAndFailedSaveNeverShowsTheRequestedMode() {
        val mode = mutableStateOf(SessionPermissionMode.APPROVAL_REQUIRED)
        var saveSucceeds = false
        var changes = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    ConversationComposer(
                        "draft",
                        {},
                        true,
                        false,
                        ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                        permissionMode = mode.value,
                        onPermissionMode = { next ->
                            changes++
                            if (saveSucceeds) mode.value = next
                            saveSucceeds
                        },
                        modelSelector = {
                            ComposerModelMenu(
                                emptyList(),
                                "provider",
                                "long-model-name",
                                false,
                                { _, _ -> },
                            )
                        },
                    )
                }
            }
        }
        val input = compose.onNodeWithTag("chat-input").getUnclippedBoundsInRoot()
        val permission = compose.onNodeWithTag("chat-permission-menu").getUnclippedBoundsInRoot()
        val model = compose.onNodeWithTag("chat-model-menu").getUnclippedBoundsInRoot()
        assertTrue(permission.top >= input.bottom && permission.right <= model.left)
        compose
            .onNodeWithTag("chat-permission-menu")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag("chat-permission-FULL_ACCESS").performClick()
        compose.onNodeWithTag("chat-permission-notice").assertIsDisplayed()
        assertEquals(SessionPermissionMode.APPROVAL_REQUIRED, mode.value)
        saveSucceeds = true
        compose.onNodeWithTag("chat-permission-menu").performClick()
        compose.onNodeWithTag("chat-permission-FULL_ACCESS").performClick()
        compose.waitForIdle()
        assertEquals(SessionPermissionMode.FULL_ACCESS, mode.value)
        assertEquals(2, changes)
        compose.onNodeWithTag("chat-stop").assertIsEnabled()
    }
}
