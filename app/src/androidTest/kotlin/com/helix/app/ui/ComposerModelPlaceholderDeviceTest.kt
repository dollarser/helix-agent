package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerModelPlaceholderDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun emptyModelHintLivesInsideEditorAndNeverReplacesDraftOrSends() {
        val input = mutableStateOf("")
        val selected = mutableStateOf(false)
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    input.value,
                    { input.value = it },
                    false,
                    true,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    modelSelector = {
                        TextButton(
                            { selected.value = true },
                            Modifier.testTag("chat-model-menu"),
                        ) { Text("Model") }
                    },
                    availability = ComposerAvailability(modelSelected = selected.value),
                )
            }
        }
        compose
            .onNodeWithTag("chat-input-placeholder", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.chat_input_placeholder))
            .assert(hasAnyAncestor(hasTestTag("chat-input")))
        compose.onNodeWithTag("chat-select-model-reminder").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        assertEquals(0, sends)
        compose.onNodeWithTag("chat-input").assertIsEnabled().performTextInput("保留输入")
        compose.onNodeWithTag("chat-input").assertTextEquals("保留输入")
        compose.onNodeWithTag("chat-input-placeholder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat-select-model-reminder").assertDoesNotExist()
        compose.onNodeWithTag("chat-send").performClick()
        assertEquals(0, sends)
        compose.onNodeWithTag("chat-input").assertTextEquals("保留输入")
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-send").assertIsEnabled()
        assertEquals(0, sends)
        compose.onNodeWithTag("chat-input").performTextClearance()
        compose
            .onNodeWithTag("chat-input-placeholder", useUnmergedTree = true)
            .assertTextEquals(context.getString(R.string.chat_input_placeholder))
        assertEquals(0, sends)
    }

    @Test fun concreteDeliveryFailureRemainsVisibleBesidePreservedInput() {
        compose.setContent {
            MaterialTheme {
                ConversationComposer(
                    "保留输入",
                    {},
                    false,
                    false,
                    ComposerActions(onFile = {}, onVoice = {}, onSend = {}, onStop = {}),
                    availability =
                        ComposerAvailability(
                            delivery = false,
                            deliveryReason = R.string.composer_temporarily_unavailable,
                        ),
                )
            }
        }
        compose.onNodeWithTag("chat-input").assertTextEquals("保留输入")
        compose.onNodeWithTag("chat-send-status").assertIsDisplayed()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        compose.onNodeWithTag("chat-select-model-reminder").assertDoesNotExist()
    }
}
