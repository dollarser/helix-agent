package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Model management only updates candidates; no conversation action or external service is used. */
class ProviderModelSelectionFeedbackDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelManagementSavesCandidatesWithoutApplyingToAConversation() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                f.service.saveSelectedModels("p", listOf("b"))
                val row =
                    f.service.rows.value
                        .single()
                val dismissed = AtomicBoolean(false)
                compose.setContent {
                    MaterialTheme {
                        ProviderModelsDialog(
                            row,
                            f.service,
                            onDismiss = { dismissed.set(true) },
                        )
                    }
                }
                val list = compose.onNodeWithTag("provider-model-list")
                list.performScrollToNode(hasTestTag("provider-model-details-b"))
                compose.onNodeWithTag("provider-model-details-b").performClick()
                compose.onNodeWithTag("provider-model-use-b").assertDoesNotExist()
                list.performScrollToNode(hasTestTag("provider-model-choice-b"))
                compose.onNodeWithTag("provider-model-choice-b").performClick()
                compose.onNodeWithTag("provider-models-save").performClick()
                compose.waitUntil(5000) { dismissed.get() }
                assertEquals(
                    emptyList<String>(),
                    f.service.rows.value
                        .single()
                        .conversationModels,
                )
                assertEquals(0, f.generations)
            }
        }
}
