package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** UI-only fixture. No model, network, permissions or device controls are invoked. */
class TaskFirstEntryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unconfiguredUserCanChooseATaskDraftAndThenChooseAModel() {
        var draft = ""
        var modelChoices = 0
        compose.setContent {
            MaterialTheme {
                EmptyConversationHint(
                    goalMode = false,
                    hasProvider = false,
                    onSelectPrompt = { draft = it },
                    onChooseModel = { modelChoices++ },
                )
            }
        }
        compose.onNodeWithTag("chat-starter-prompts").assertIsDisplayed()
        compose.onNodeWithTag("starter-prompt-${R.string.chat_task_document}").performClick()
        compose.runOnIdle {
            assertFalse(draft.isBlank())
            assertEquals(0, modelChoices)
        }
        compose.onNodeWithTag("starter-choose-model").performClick()
        compose.runOnIdle {
            assertEquals(1, modelChoices)
            assertFalse(draft.isBlank())
        }
    }

    @Test fun selectedModelDoesNotShowAnUnnecessarySetupRequirement() {
        compose.setContent {
            MaterialTheme { EmptyConversationHint(false, true, onSelectPrompt = {}) }
        }
        compose.onNodeWithTag("starter-choose-model").assertDoesNotExist()
        compose.onNodeWithTag("chat-starter-prompts").assertIsDisplayed()
    }
}
