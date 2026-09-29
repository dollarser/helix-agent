package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.provider.catalog.ProviderTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderSettingsFormDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun optionalKeyAndMultipleModelChoicesRemainEditable() {
        val state =
            mutableStateOf(
                ProviderForm(
                    null,
                    ProviderTemplateCatalog.sglang,
                    ProviderForm.FormFields("Private server", "https://example.test/v1", "", "", "", ""),
                    false,
                    false,
                    null,
                ),
            )
        compose.setContent {
            MaterialTheme {
                ProviderFormDialog(
                    form = state.value,
                    saving = false,
                    onField = { state.value = it },
                    onSave = {},
                    onDismiss = {},
                    discoveryState = ProviderFormDiscovery(listOf("model-a", "model-b")),
                )
            }
        }
        compose.onNodeWithTag("provider-form-key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-form-save").assertIsEnabled()
        compose
            .onNodeWithTag("provider-model-choice-model-a")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        compose
            .onNodeWithTag("provider-model-choice-model-b")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        compose.runOnIdle { assertEquals(setOf("model-a", "model-b"), state.value.selectedModels) }
        compose.onNodeWithTag("provider-form-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("provider-form-key").performScrollTo().assertIsDisplayed()
    }
}
