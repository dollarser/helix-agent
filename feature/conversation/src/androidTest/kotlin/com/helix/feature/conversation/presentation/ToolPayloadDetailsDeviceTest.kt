package com.helix.feature.conversation.presentation

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ToolCallState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolPayloadDetailsDeviceTest : ConversationDeviceTestFixture(usesClipboard = true) {
    @get:Rule val compose = createComposeRule()

    @Test fun shortReadableDetailsHaveNoInternalMetadataRepeatedHeadingOrSecondInspector() {
        val text = "Instructions explain snapshotHash and callId as business text."
        val result =
            """
            {
                "name":"notes", "snapshotHash":"private-hash", "ownerId":"private-owner", "instructions":"$text"
            }
            """.trimIndent()
        render(result)
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-result-call").assertTextContains(text, substring = true)
        compose.onNodeWithTag("tool-row-name-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-intent-detail-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-call-id-detail-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-inspector-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-content-full-call").assertDoesNotExist()
        compose.onAllNodesWithText("private-hash", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("private-owner", substring = true).assertCountEquals(0)
    }

    @Test fun longResultHasOneFullContentPathAndCopiesReadableContentOnly() {
        val body = "Readable line with snapshotHash as content.\n".repeat(30) + "FINAL BUSINESS RESULT"
        val result =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive("notes"))
                    put("snapshotHash", kotlinx.serialization.json.JsonPrimitive("private-hash"))
                    put("instructions", kotlinx.serialization.json.JsonPrimitive(body))
                }.toString()
        render(result.take(100) + "…") { _, _ -> ToolInspectionContent(arguments, result) }
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onAllNodesWithTag("tool-content-full-call").assertCountEquals(1)
        compose.onNodeWithTag("tool-content-full-call").performScrollTo().performClick()
        compose.onNodeWithTag("tool-content-text").assertTextContains(body, substring = true)
        compose.onAllNodesWithText("private-hash", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("tool-content-copy").performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied =
                clipboard.primaryClip!!
                    .getItemAt(0)
                    .text
                    .toString()
            assertTrue(copied.contains("FINAL BUSINESS RESULT"))
            assertTrue(copied.contains("snapshotHash as content"))
            assertFalse(copied.contains("private-hash"))
        }
        compose.onNodeWithTag("tool-content-close").performClick()
        compose
            .onNodeWithTag("tool-details-call")
            .performScrollTo()
            .performClick()
            .performClick()
        compose.onAllNodesWithTag("tool-content-full-call").assertCountEquals(1)
    }

    @Test fun failedFullReadCanRetryWithoutShowingRawTruncatedMetadata() {
        var attempts = 0
        render("""{"snapshotHash":"private-hash","instructions":"…""") { _, _ ->
            attempts++
            if (attempts == 1) throw java.io.IOException("fixture unavailable")
            ToolInspectionContent(arguments, "Unable to load the requested document; check its path.")
        }
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-content-load-call").assertExists()
        compose.onAllNodesWithText("private-hash", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("tool-content-retry-call").performClick()
        compose.onNodeWithTag("tool-row-result-call").assertTextContains("check its path", substring = true)
        compose.onNodeWithTag("tool-content-retry-call").assertDoesNotExist()
    }

    @Test fun concreteFailureIsNotRemovedByMetadataWords() {
        val failure = "Unable to read snapshotHash from the requested file. No action was executed."
        render(failure, state = ToolCallState.FAILED)
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-result-call").assertTextContains(failure, substring = true)
        compose.onNodeWithTag("tool-row-state-call", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tool-content-full-call").assertDoesNotExist()
    }

    private val arguments = """{"name":"notes","snapshotHash":"private-hash","ownerId":"private-owner"}"""

    private fun render(
        result: String,
        state: ToolCallState = ToolCallState.COMPLETED,
        read: suspend (String, String) -> ToolInspectionContent? = { _, _ -> null },
    ) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                    ToolTimelineItem(
                        ToolTimelineRow(
                            "turn",
                            "call",
                            "skills.read",
                            arguments,
                            state.name,
                            result,
                            null,
                            modelIntent = "Read notes",
                            state = state,
                        ),
                        ConversationIntents(
                            onSend = {},
                            onStop = {},
                            onDismissBlocked = {},
                            onApproveApproval = {},
                            onDenyApproval = {},
                            onStageAttachment = {},
                            onRemoveAttachment = {},
                            onSetMode = {},
                            onInspectToolResult = read,
                        ),
                    )
                }
            }
        }
    }
}
