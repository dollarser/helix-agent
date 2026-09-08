package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.helix.app.R
import com.helix.core.model.TurnState
import org.junit.Rule
import org.junit.Test

class TurnProgressDeviceTest {
    private val state = mutableStateOf(TurnState.WAITING_MODEL)
    private val approval = mutableStateOf(false)

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = { MaterialTheme { TurnProgressLabel(state.value, approval.value) } }
        }

    @Test fun approvalExecutionAndCancellationShowTheirOwnProgress() {
        assertLabel(R.string.chat_waiting_model)
        compose.runOnIdle {
            state.value = TurnState.RUNNING_TOOL
            approval.value = true
        }
        assertLabel(R.string.chat_waiting_approval)
        compose.runOnIdle { approval.value = false }
        assertLabel(R.string.chat_running_tool)
        compose.runOnIdle { state.value = TurnState.RECORDING_TOOL_RESULT }
        assertLabel(R.string.chat_recording_result)
        compose.runOnIdle {
            state.value = TurnState.CANCELLING
            approval.value = true
        }
        assertLabel(R.string.chat_cancelling)
        compose.runOnIdle { state.value = TurnState.CANCELLED }
        assertLabel(R.string.chat_cancelled)
        compose.runOnIdle { state.value = TurnState.COMPLETED }
        compose.onNodeWithTag("chat-turn-progress").assertDoesNotExist()
    }

    private fun assertLabel(resource: Int) {
        compose
            .onNodeWithTag("chat-turn-progress")
            .assertIsDisplayed()
            .assertTextEquals(compose.activity.getString(resource))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
