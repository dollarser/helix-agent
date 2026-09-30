package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.chat.SessionModelSelectionResult
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real preference service with a controlled session receipt; no external service is used. */
class ProviderModelSelectionFeedbackDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedSelectionStaysOpenOnBusyAndClosesOnlyAfterAppliedReceipt() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                f.service.saveSelectedModels("p", listOf("b"))
                f.service.runConnectionTest("p", "b")
                val row =
                    f.service.rows.value
                        .single()
                val result = mutableStateOf(SessionModelSelectionResult.BUSY)
                val dismissed = AtomicBoolean(false)
                val calls = AtomicInteger()
                compose.setContent {
                    MaterialTheme {
                        ProviderModelsDialog(
                            row,
                            f.service,
                            true,
                            onUseCurrentSession = { _, _ ->
                                calls.incrementAndGet()
                                result.value
                            },
                            onDismiss = { dismissed.set(true) },
                        )
                    }
                }
                val list = compose.onNodeWithTag("provider-model-list")
                list.performScrollToNode(hasTestTag("provider-model-details-b"))
                compose.onNodeWithTag("provider-model-details-b").performClick()
                list.performScrollToNode(hasTestTag("provider-model-use-b"))
                compose.onNodeWithTag("provider-model-use-b").performClick()
                compose.waitUntil(5000) { calls.get() == 1 }
                list.performScrollToNode(hasTestTag("provider-model-selection-result"))
                compose.onNodeWithTag("provider-model-selection-result").assertIsDisplayed()
                assertFalse(dismissed.get())
                assertEquals(
                    listOf("b"),
                    f.service.rows.value
                        .single()
                        .conversationModels,
                )
                compose.runOnIdle { result.value = SessionModelSelectionResult.APPLIED }
                list.performScrollToNode(hasTestTag("provider-model-use-b"))
                compose.onNodeWithTag("provider-model-use-b").performClick()
                compose.waitUntil(5000) { dismissed.get() }
                assertEquals(2, calls.get())
                assertEquals(1, f.generations)
            }
        }
}
