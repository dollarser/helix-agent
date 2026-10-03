package com.helix.app.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.helix.app.chat.ToolTimelineRow
import org.junit.Rule
import org.junit.Test

class ToolTimelineScrollStateDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expandedToolSurvivesScrollingOutOfComposition() {
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.height(200.dp).testTag("scroll-fixture")) {
                    item(key = "original-call") {
                        ToolTimelineItem(
                            ToolTimelineRow("turn", "call", "read", "{}", "完成", "result", null),
                            ConversationIntents(
                                onSend = {},
                                onStop = {},
                                onDismissBlocked = {},
                                onApproveApproval = {},
                                onDenyApproval = {},
                                onStageAttachment = {},
                                onRemoveAttachment = {},
                                onSetMode = {},
                                onSetChatTools = {},
                            ),
                        )
                    }
                    items(30) { Text("Other row $it", Modifier.height(100.dp)) }
                }
            }
        }
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-name-call").assertExists()
        compose.onNodeWithTag("scroll-fixture").performScrollToIndex(30)
        compose.onNodeWithTag("tool-row-name-call").assertDoesNotExist()
        compose.onNodeWithTag("scroll-fixture").performScrollToIndex(0)
        compose.onNodeWithTag("tool-row-name-call").assertExists()
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-name-call").assertDoesNotExist()
    }
}
