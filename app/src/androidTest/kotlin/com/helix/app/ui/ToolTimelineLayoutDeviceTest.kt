package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.app.chat.ToolTimelineRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolTimelineLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longToolNameKeepsItsStateVisibleOnANarrowScreen() {
        render(
            ToolTimelineRow("turn", "call", "mcp.documents.search_workspace_records", "{}", "执行失败", "No match", null),
        )
        val row = compose.onNodeWithTag("tool-row-call").getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("tool-row-state-call")
        status.assertIsDisplayed()
        val bounds = status.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= row.left && bounds.right <= row.right)
    }

    @Test fun longArgumentsAndResultsCanBeExpandedWithoutLosingTheirContent() {
        val args = "{\"path\":\"workspace/long-document.txt\"}\n".repeat(20)
        val result = "A long tool result with its original content.\n".repeat(20)
        render(ToolTimelineRow("turn", "call", "read", args, "已执行成功", result, null))
        verifyExpansion("tool-row-args-call", args)
        verifyExpansion("tool-row-result-call", result)
    }

    private fun verifyExpansion(
        tag: String,
        text: String,
    ) {
        val node = compose.onNodeWithTag(tag)
        val before = node.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithTag("$tag-toggle").performScrollTo().performClick()
        node.assertTextContains(text, substring = true)
        val after = node.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(after > before)
        compose.onNodeWithTag("$tag-toggle").performScrollTo().performClick()
    }

    private fun render(row: ToolTimelineRow) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        ToolTimelineItem(row, timelineIntents())
                    }
                }
            }
        }
    }
}

private fun timelineIntents() =
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
        onSetMode = {},
        onSetChatTools = {},
    )
