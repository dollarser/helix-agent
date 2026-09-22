package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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

    @Test fun steeringRequiresExplicitSelectionAndDoesNotRebindWhenTurnChanges() {
        val delivery = mutableStateOf(SessionInputDelivery.QUEUE)
        val target = mutableStateOf<String?>(null)
        val active = mutableStateOf<String?>("first")
        compose.setContent {
            MaterialTheme {
                SessionInputDeliverySelector(delivery.value, target.value, active.value, true) { selected, turn ->
                    delivery.value = selected
                    target.value = turn
                }
            }
        }
        compose.onNodeWithTag("session-input-delivery-steer").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(SessionInputDelivery.STEER, delivery.value)
            assertEquals("first", target.value)
            active.value = "second"
        }
        compose.onNodeWithTag("session-input-steer-expired").assertIsDisplayed()
        compose.runOnIdle { assertEquals("first", target.value) }
        compose.onNodeWithTag("session-input-delivery-steer").performClick()
        compose.runOnIdle { assertEquals("second", target.value) }
        compose.onNodeWithTag("session-input-delivery-queue").performClick()
        compose.runOnIdle {
            assertEquals(SessionInputDelivery.QUEUE, delivery.value)
            assertNull(target.value)
            active.value = null
        }
        compose.onNodeWithTag("session-input-delivery-selector").assertDoesNotExist()
    }

    @Test fun nonEmptyDeliveryCountRendersPendingAndHistory() {
        compose.setContent {
            MaterialTheme {
                SessionInputDeliveryCountText(pendingCount = 2, appendedCount = 1)
            }
        }
        compose
            .onNodeWithTag("session-input-delivery-count")
            .assertIsDisplayed()
            .assertTextContains("2", substring = true)
            .assertTextContains("1", substring = true)
    }
}
