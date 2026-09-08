package com.helix.app.proot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.chat.ToolTimelineRow
import com.helix.app.ui.ProotRecoveryActions
import com.helix.app.ui.ProotResultPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProotResultUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun viewActionUsesTheOriginalIdentity() {
        var selected: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                Column {
                    ProotRecoveryActions(
                        ToolTimelineRow("turn", "call", "bash", "{}", "INTERRUPTED", null, null),
                        { _, _, _ -> error("Unexpected query or stop") },
                        { turn, call -> selected = turn to call },
                    )
                }
            }
        }
        compose.onNodeWithTag("proot-result-call").performClick()
        compose.runOnIdle { assertEquals("turn" to "call", selected) }
    }

    @Test
    fun acknowledgementUsesOriginalIdentityAndDisablesWhileBusy() {
        val busy = mutableStateOf(false)
        val acknowledged = mutableStateOf<Boolean?>(null)
        var selected: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                com.helix.app.ui.ProotAcknowledgementActions(
                    ToolTimelineRow("turn", "call", "bash", "{}", "NEEDS_REVIEW", null, null)
                        .copy(prootRecoveryBusy = busy.value),
                    acknowledged.value,
                ) { turn, call -> selected = turn to call }
            }
        }
        compose.onNodeWithTag("proot-ack-call").performClick()
        compose.runOnIdle {
            assertEquals("turn" to "call", selected)
            busy.value = true
        }
        compose.onNodeWithTag("proot-ack-call").assertIsNotEnabled()
        compose.runOnIdle {
            busy.value = false
            acknowledged.value = true
        }
        compose.onNodeWithTag("proot-ack-call").assertDoesNotExist()
    }

    @Test
    fun outputSupportsPagingWithoutDispatchingContent() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProotResultPanel(
                        ProotRecoveredOutput("x".repeat(5000) + "END", "error", false, emptyList()),
                        "call",
                    )
                }
            }
        }
        compose.onNodeWithTag("proot-previous-call").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("proot-next-call").performScrollTo().performClick()
        compose.onNodeWithTag("proot-result-text-call").assertTextContains("END", substring = true)
        compose.onNodeWithTag("proot-next-call").performScrollTo().assertIsNotEnabled()
    }
}
