package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.core.storage.repository.SessionInputDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SessionInputDeliveryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun queuedPreviewIsVisibleAndSendNowIsExplicitAndBoundToDisplayedTurn() {
        val record = queuedInput()
        val active = mutableStateOf<String?>("first")
        val busy = mutableStateOf(false)
        val sent = mutableListOf<Pair<String, String?>>()
        val edited = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SessionInputQueuePreview(
                    listOf(record),
                    mapOf("queued" to "Please also check the result"),
                    mapOf("queued" to busy.value),
                    active.value,
                    actions =
                        SessionInputQueueActions(
                            edit = { edited += it.inputId },
                            delete = { deleted += it.inputId },
                            send = { input, target -> sent += input.inputId to target },
                        ),
                )
            }
        }
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
        compose.onNodeWithTag("session-input-edit-action-queued").performClick()
        compose.onNodeWithTag("session-input-delete-queued").performClick()
        compose.runOnIdle {
            assertEquals(listOf("queued"), edited)
            assertEquals(listOf("queued"), deleted)
        }
        compose.runOnIdle { assertEquals(emptyList<Pair<String, String?>>(), sent) }
        compose.onNodeWithTag("session-input-send-now-queued").performClick()
        compose.runOnIdle {
            assertEquals(listOf("queued" to "first"), sent)
            busy.value = true
        }
        compose.onNodeWithTag("session-input-send-now-queued").assertIsNotEnabled()
        compose.runOnIdle {
            active.value = null
            busy.value = false
        }
        compose.onNodeWithTag("session-input-send-now-queued").performClick()
        compose.runOnIdle { assertEquals("queued" to null, sent.last()) }
        compose.onNodeWithTag("session-input-preview-queued").assertIsDisplayed()
    }

    private fun queuedInput() =
        com.helix.core.storage.repository.SessionInputRecord(
            schemaVersion = 1,
            inputId = "queued",
            sessionId = "session",
            sequence = 1,
            delivery = SessionInputDelivery.QUEUE,
            expectedTurnId = null,
            revision = 0,
            textRef = "fixture",
            textBytes = 10,
            attachments = emptyList(),
            configuration =
                com.helix.core.storage.repository
                    .InputConfiguration("p", "m", "ACT", "fixture"),
            state = com.helix.core.storage.repository.SessionInputState.PENDING,
            consumedTurnId = null,
            messageId = null,
            requestModelCallId = null,
            blockedReason = null,
            createdAt = 0,
            updatedAt = 0,
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
