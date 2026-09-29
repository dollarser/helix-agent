package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderContextSettings
import com.helix.app.provider.ProviderRowUi
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** In-memory actions only: no accounts, network requests or user settings are changed. */
class ProviderContextRecoveryDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val row =
        ProviderRowUi(
            "fixture",
            "Fixture",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://example.test",
            ProviderResidence.PUBLIC_CLOUD,
            "model-a",
            false,
            false,
            ConnectionTestStatus.Untested,
            null,
            listOf("model-b"),
            emptyList(),
        )

    @Test fun loadFailureDisablesSaveAndRetryRecoversWithoutLeakingException() {
        var attempts = 0
        compose.setContent {
            MaterialTheme {
                ProviderContextEditor(
                    row,
                    load = {
                        attempts++
                        if (attempts == 1) error("secret-response-body")
                        ProviderContextSettings()
                    },
                    discover = { ProviderContextSettings() },
                    save = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("provider-context-save").assertIsNotEnabled()
        compose.onNodeWithText("secret-response-body").assertDoesNotExist()
        compose.onNodeWithTag("provider-context-retry").performScrollTo().performClick()
        compose.onNodeWithTag("provider-context-save").assertIsEnabled()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun discoveryRetryPreservesUnsavedEdits() {
        var attempts = 0
        compose.setContent {
            MaterialTheme {
                ProviderContextEditor(
                    row,
                    load = { ProviderContextSettings() },
                    discover = {
                        attempts++
                        if (attempts == 1) error("unavailable")
                        ProviderContextSettings(serverWindow = 64000)
                    },
                    save = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("provider-context-save").assertIsEnabled()
        compose.onNodeWithTag("provider-context-threshold").performScrollTo().performTextReplacement("70")
        compose.onNodeWithTag("provider-context-retry").performScrollTo().performClick()
        compose.onNodeWithTag("provider-context-threshold").assertTextContains("70")
        compose.onNodeWithTag("provider-context-load-error").assertDoesNotExist()
    }

    @Test fun failedSaveRemainsEditableAndInFlightSaveCannotBeDuplicated() {
        var attempts = 0
        var dismissed = 0
        val finish = CompletableDeferred<Unit>()
        compose.setContent {
            MaterialTheme {
                ProviderContextEditor(
                    row,
                    load = { ProviderContextSettings() },
                    discover = { ProviderContextSettings() },
                    save = { _, settings ->
                        attempts++
                        if (attempts == 1) error("secret-response-body")
                        assertEquals(70, settings.triggerPercent)
                        finish.await()
                    },
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.onNodeWithTag("provider-context-save").performClick()
        compose.onNodeWithTag("provider-context-save-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("secret-response-body").assertDoesNotExist()
        compose.onNodeWithTag("provider-context-threshold").performScrollTo().performTextReplacement("70")
        compose.onNodeWithTag("provider-context-save").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("provider-context-model").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(0, dismissed)
            finish.complete(Unit)
        }
        compose.waitUntil { dismissed == 1 }
    }
}
