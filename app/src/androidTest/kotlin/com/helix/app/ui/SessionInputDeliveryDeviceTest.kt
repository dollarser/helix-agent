package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.core.model.input.SessionInputDelivery
import com.helix.feature.conversation.input.PendingInputView
import com.helix.feature.conversation.presentation.SessionInputQueueActions
import com.helix.feature.conversation.presentation.SessionInputQueuePreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionInputDeliveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun queuedPreviewIsVisibleAndSendNowIsExplicitAndBoundToDisplayedTurn() {
        val record = queuedInput()
        val active = mutableStateOf<String?>("first")
        val busy = mutableStateOf(false)
        val sent = mutableListOf<Triple<String, Long, String?>>()
        val edited = mutableListOf<Pair<String, Long>>()
        val deleted = mutableListOf<Pair<String, Long>>()
        compose.setContent {
            MaterialTheme {
                SessionInputQueuePreview(
                    listOf(record),
                    mapOf("queued" to "Please also check the result"),
                    mapOf("queued" to busy.value),
                    active.value,
                    actions =
                        SessionInputQueueActions(
                            edit = { edited += it.inputId to it.revision },
                            delete = { deleted += it.inputId to it.revision },
                            send = { input, target -> sent += Triple(input.inputId, input.revision, target) },
                        ),
                )
            }
        }
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
        compose.onNodeWithTag("session-input-edit-action-queued").performClick()
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        compose.runOnIdle {
            assertEquals(listOf("queued" to 7L), edited)
            assertEquals(listOf("queued" to 7L), deleted)
        }
        compose.runOnIdle { assertEquals(emptyList<Triple<String, Long, String?>>(), sent) }
        compose.onNodeWithTag("session-input-send-now-queued").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Triple("queued", 7L, "first")), sent)
            busy.value = true
        }
        compose.onNodeWithTag("session-input-send-now-queued").assertIsNotEnabled()
        compose.runOnIdle {
            active.value = null
            busy.value = false
        }
        compose.onNodeWithTag("session-input-send-now-queued").performClick()
        compose.runOnIdle { assertEquals(Triple("queued", 7L, null), sent.last()) }
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
    }

    private fun queuedInput() =
        PendingInputView(
            inputId = "queued",
            delivery = SessionInputDelivery.QUEUE,
            revision = 7,
            needsAttention = false,
        )

    @Test fun sessionPreferenceIsAlwaysVisibleAndCanBeChangedWhileIdle() {
        val immediate = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SessionInputDeliverySelector(immediate.value, true) { selected ->
                    immediate.value = selected
                    true
                }
            }
        }
        compose.onNodeWithTag("session-input-delivery-selector").assertIsDisplayed()
        compose.onNodeWithTag("session-input-delivery-steer").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, immediate.value) }
        compose.onNodeWithTag("session-input-delivery-queue").performClick()
        compose.runOnIdle { assertEquals(false, immediate.value) }
        compose.onNodeWithTag("session-input-delivery-selector").assertIsDisplayed()
    }

    @Test fun emptyQueueHasNoPersistentManagementEntry() {
        compose.setContent {
            MaterialTheme {
                SessionInputQueuePreview(
                    emptyList(),
                    emptyMap(),
                    emptyMap(),
                    null,
                    SessionInputQueueActions({}, {}, { _, _ -> }),
                )
            }
        }
        compose.onNodeWithTag("session-input-manage").assertDoesNotExist()
        compose.onNodeWithTag("session-input-queue-toggle").assertDoesNotExist()
        compose.onNodeWithTag("session-input-preview-queued").assertDoesNotExist()
    }
}
