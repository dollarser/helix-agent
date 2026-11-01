package com.helix.app.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.chat.DisplayExecutionState
import com.helix.app.chat.DisplayExecutionUi
import com.helix.app.chat.ToolTimelinePosition
import com.helix.app.chat.ToolTimelineRow
import com.helix.core.model.ToolCallState
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

class ConversationDisplayRotationDeviceTest {
    private val status =
        mutableStateOf(DisplayExecutionUi("s", "rotation", DisplayExecutionState.ACTIVE, "com.example.fixture"))
    private val rows =
        (0..4).map { i ->
            ToolTimelineRow(
                "rotation",
                "r$i",
                "read",
                "{}",
                "调用完成",
                "fixture",
                null,
                position = ToolTimelinePosition(1, i),
                state = ToolCallState.COMPLETED,
            )
        }
    private val intents =
        ConversationIntents(
            {},
            {},
            onDismissBlocked = {},
            onApproveApproval = {},
            onDenyApproval = {},
            onStageAttachment = {},
            onRemoveAttachment = {},
            onSetMode = {},
        )

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                MaterialTheme {
                    var draft by rememberSaveable { mutableStateOf("Synthetic draft") }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        DisplayExecutionStatus(status.value)
                        BasicTextField(draft, { draft = it }, Modifier.testTag("rotation-draft"))
                        ToolTimelineGroup(rows, intents, null)
                    }
                }
            }
        }

    @After fun clearFixture() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun actualLandscapeRecreationRestoresDraftExpandedToolsAndLiveReleaseUpdates() {
        val original = compose.activity
        val orientation = original.requestedOrientation
        try {
            compose.onNodeWithTag("rotation-draft").performTextReplacement("Preserve this synthetic draft")
            compose.onNodeWithTag("tool-group-toggle-r0").performClick()
            compose.onNodeWithTag("tool-row-r0").assertIsDisplayed()
            compose.runOnUiThread { original.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    compose.activity !== original
            }
            assertNotSame(original, compose.activity)
            compose.onNodeWithTag("rotation-draft").assertTextContains("Preserve this synthetic draft")
            compose.onNodeWithTag("tool-row-r0").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { status.value = status.value.copy(state = DisplayExecutionState.RELEASED) }
            compose.onNodeWithTag("display-live-rotation").performScrollTo().assertTextContains("已释放", substring = true)
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = orientation }
        }
    }
}
