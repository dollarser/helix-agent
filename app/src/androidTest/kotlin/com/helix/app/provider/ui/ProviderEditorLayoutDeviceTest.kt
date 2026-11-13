package com.helix.app.provider.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.app.provider.ProviderModelSelection
import com.helix.provider.catalog.ProviderTemplateCatalog
import com.helix.ui.shared.IndicatedLazyColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ProviderEditorLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowLargeTextEditorKeepsActionsVisible() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    ProviderEditorDialog(
                        onDismissRequest = {},
                        title = { Text("Connection and model settings") },
                        text = {
                            IndicatedLazyColumn(Modifier.fillMaxSize()) {
                                items(40) { Text("Model $it") }
                            }
                        },
                        confirmButton = {
                            Button({}, Modifier.testTag("save")) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton({}, Modifier.testTag("cancel")) { Text("Cancel") }
                        },
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
        compose.onNodeWithTag("save").assertIsDisplayed()
        compose.onNodeWithTag("cancel").assertIsDisplayed()
    }

    @Test fun arrivingCatalogDoesNotOpenSuggestionsOrScrollTheConnectionAway() {
        val discovery = mutableStateOf(ProviderFormDiscovery())
        compose.setContent {
            MaterialTheme {
                ProviderFormDialog(
                    form = syntheticForm(),
                    saving = false,
                    onField = {},
                    onSave = {},
                    onDismiss = {},
                    discoveryState = discovery.value,
                )
            }
        }
        compose.onNodeWithTag("provider-form-name").assertIsDisplayed()
        compose.runOnIdle { discovery.value = ProviderFormDiscovery(listOf("a", "b")) }
        compose.onNodeWithTag("provider-form-name").assertIsDisplayed()
        compose.onNodeWithTag("provider-model-option-a").assertDoesNotExist()
        compose.onNodeWithTag("provider-form-save").assertIsDisplayed()
    }

    @Test fun cancellingManualEntryDoesNotSaveHiddenModel() {
        val draft =
            ProviderModelsEditorState(ProviderModelSelection()).apply {
                addingModel = true
                manual = "unsaved-model"
                manualDisplayName = "Unsaved name"
            }
        compose.setContent {
            MaterialTheme { ProviderModelAdder(draft, emptyList(), enabled = true) }
        }
        compose.onNodeWithTag("provider-model-add-cancel").performClick()
        compose.onNodeWithTag("provider-model-add-entry").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(draft.addingModel)
            assertEquals("", draft.manual)
            assertEquals("", draft.manualDisplayName)
            assertEquals(emptyList<String>(), draft.selectionForSave().models)
        }
    }

    @Test fun actualFormAtNarrowLargeTextSelectsExpandsAndCancelsWithoutSaving() {
        val draft = ProviderModelsEditorState(ProviderModelSelection())
        var saves = 0
        var cancels = 0
        showNarrowForm(draft, { saves++ }, { cancels++ })
        assertFormActionsVisible()
        formNode("provider-model-choice-b").performClick()
        compose.onNodeWithTag("provider-model-choice-b").assertIsOn()
        formNode("provider-model-details-b").performClick()
        formNode("provider-model-name-b").assertIsDisplayed()
        formNode("provider-model-details-b").performClick()
        compose.onNodeWithTag("provider-model-name-b").assertDoesNotExist()
        assertFormActionsVisible()
        compose.onNodeWithTag("provider-form-cancel").performClick()
        compose.runOnIdle {
            assertEquals(0, saves)
            assertEquals(1, cancels)
        }
    }

    @Test fun actualFormCancelsManualEntryBeforeSavingSelection() {
        val draft = ProviderModelsEditorState(ProviderModelSelection())
        var saved: ProviderModelSelection? = null
        showNarrowForm(draft, { saved = draft.selectionForSave() }, {})
        formNode("provider-model-choice-a").performClick()
        formNode("provider-model-add-entry").performClick()
        formNode("provider-model-manual").performTextReplacement("unsaved-model")
        formNode("provider-model-display-name").performTextReplacement("Unsaved name")
        formNode("provider-model-add-cancel").performClick()
        compose.onNodeWithTag("provider-model-manual").assertDoesNotExist()
        assertFormActionsVisible()
        compose.onNodeWithTag("provider-form-save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("a"), saved?.models)
            assertFalse(saved?.displayNames?.containsKey("unsaved-model") == true)
        }
    }

    private fun showNarrowForm(
        draft: ProviderModelsEditorState,
        onSave: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val form = mutableStateOf(syntheticForm())
        compose.setContent {
            MaterialTheme {
                NarrowLargeText {
                    ProviderFormDialog(
                        form = form.value,
                        saving = false,
                        onField = { form.value = it },
                        onSave = onSave,
                        onDismiss = onCancel,
                        discoveryState = ProviderFormDiscovery(listOf("a", "b")),
                        models = draft,
                    )
                }
            }
        }
    }

    private fun assertFormActionsVisible() {
        compose.onNodeWithTag("provider-form-save").assertIsDisplayed()
        compose.onNodeWithTag("provider-form-cancel").assertIsDisplayed()
    }

    private fun formNode(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("provider-form-scroll").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
}

/** Density is local to this composition, including dialogs; the device settings stay unchanged. */
@Composable
@Suppress("FunctionName")
internal fun NarrowLargeText(content: @Composable () -> Unit) {
    val widthPixels = LocalContext.current.resources.displayMetrics.widthPixels
    CompositionLocalProvider(LocalDensity provides Density(widthPixels / 320f, 1.6f), content = content)
}

internal fun syntheticForm() =
    ProviderForm(
        null,
        ProviderTemplateCatalog.sglang,
        ProviderForm.FormFields("演示 API 服务", "https://example.test/v1", "", "", "", ""),
        false,
        null,
    )
