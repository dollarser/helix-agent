package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.helix.provider.catalog.ProviderTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderSettingsFormDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customServiceReplacesDuplicateServerTemplates() {
        var selected: String? = null
        compose.setContent {
            MaterialTheme { TemplatePickerDialog({ selected = it.id }, {}) }
        }
        compose.onNodeWithTag("provider-template-sglang").assertDoesNotExist()
        compose.onNodeWithTag("provider-template-vllm").assertDoesNotExist()
        compose.onNodeWithTag("provider-template-ollama").assertDoesNotExist()
        compose.onNodeWithTag("provider-template-generic-openai").performClick()
        compose.runOnIdle { assertEquals("generic-openai", selected) }
    }

    @Test fun overflowIsVisibleAndSaveExplainsMissingModel() {
        val state =
            mutableStateOf(
                ProviderForm(
                    null,
                    ProviderTemplateCatalog.sglang,
                    ProviderForm.FormFields("Server", "https://example.test/v1", "", "", "", ""),
                    false,
                    null,
                ),
            )
        compose.setContent {
            MaterialTheme {
                ProviderFormDialog(
                    state.value,
                    false,
                    { state.value = it.copy(error = null) },
                    { state.value = state.value.copy(error = validateProviderForm(state.value)) },
                    {},
                    discoveryState = ProviderFormDiscovery((1..30).map { "model-$it" }),
                )
            }
        }
        compose.onNodeWithTag("provider-form-scroll-hint").assertIsDisplayed()
        compose.onNodeWithTag("provider-form-save").performClick()
        compose.onNodeWithTag("provider-form-error").assertIsDisplayed()
        compose.onNodeWithTag("provider-form-model").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ProviderFormField.MODEL, providerErrorField(state.value.error)) }
    }

    @Test fun httpSaveAndDiscoveryRemainClickableWithWarningOnly() {
        var saves = 0
        var discoveries = 0
        val state =
            mutableStateOf(
                ProviderForm(
                    null,
                    ProviderTemplateCatalog.sglang,
                    ProviderForm.FormFields("Server", "http://example.test/v1", "model", "", "", ""),
                    false,
                    null,
                ),
            )
        compose.setContent {
            MaterialTheme {
                ProviderFormDialog(
                    state.value,
                    false,
                    { state.value = it.copy(error = null) },
                    {
                        saves++
                        state.value = state.value.copy(error = validateProviderForm(state.value))
                    },
                    {},
                    onDiscover = { discoveries++ },
                )
            }
        }
        compose.onNodeWithTag("provider-form-save").assertIsEnabled().performClick()
        compose.onNodeWithTag("provider-cleartext-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-form-model").assertDoesNotExist()
        compose.onNodeWithTag("provider-model-add-entry").performScrollTo().performClick()
        compose
            .onNodeWithTag("provider-discover-models")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag("provider-form-error").assertDoesNotExist()
        compose.onNodeWithTag("provider-cleartext-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, discoveries)
        }
    }

    @Test fun missingHeaderValueExpandsOptionsAndRepeatedSaveRefocusesIt() {
        val state =
            mutableStateOf(
                ProviderForm(
                    null,
                    ProviderTemplateCatalog.sglang,
                    ProviderForm.FormFields("Server", "https://example.test/v1", "model", "X-Feature", "", ""),
                    false,
                    null,
                ),
            )
        compose.setContent {
            MaterialTheme {
                ProviderFormDialog(
                    state.value,
                    false,
                    { state.value = it.copy(error = null) },
                    { state.value = state.value.copy(error = validateProviderForm(state.value)) },
                    {},
                )
            }
        }
        compose.onNodeWithTag("provider-form-save").performClick()
        compose.onNodeWithTag("provider-form-header-value").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("provider-form-name").performScrollTo().performClick()
        compose.onNodeWithTag("provider-form-save").performClick()
        compose.onNodeWithTag("provider-form-error").assertIsDisplayed()
        compose.onNodeWithTag("provider-form-header-value").assertIsDisplayed().assertIsFocused()
    }

    @Test fun optionalKeyAndEditableModelDropdownRemainUsable() {
        val state =
            mutableStateOf(
                ProviderForm(
                    null,
                    ProviderTemplateCatalog.sglang,
                    ProviderForm.FormFields("Private server", "https://example.test/v1", "", "", "", ""),
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
        compose.onNodeWithTag("provider-form-protocol").performScrollTo().performClick()
        compose.onNodeWithTag("provider-protocol-ANTHROPIC_MESSAGES").performClick()
        compose.runOnIdle {
            assertEquals(com.helix.core.model.ProviderProtocol.ANTHROPIC_MESSAGES, state.value.protocol)
        }
        compose.onNodeWithTag("provider-form-key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("provider-form-save").assertIsEnabled()
        compose.onNodeWithTag("provider-model-add-entry").performScrollTo().performClick()
        compose.onNodeWithTag("provider-form-model").performScrollTo()
        compose.onNodeWithTag("provider-model-option-model-a").performClick()
        compose.runOnIdle { assertEquals("model-a", state.value.fields.model) }
        compose.onNodeWithTag("provider-model-option-model-b").assertDoesNotExist()
        compose.onNodeWithTag("provider-form-model").performClick()
        compose.onNodeWithTag("provider-model-option-model-b").performClick()
        compose.runOnIdle { assertEquals("model-b", state.value.fields.model) }
        compose.onNodeWithTag("provider-form-model").performTextReplacement("manual-model")
        compose.runOnIdle {
            assertEquals("manual-model", state.value.fields.model)
        }
        compose.onNodeWithTag("provider-form-model").performClick()
        compose.onNodeWithTag("provider-form-save").assertIsEnabled()
        compose.onNodeWithTag("provider-form-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("provider-form-key").performScrollTo().assertIsDisplayed()
    }
}
