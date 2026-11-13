package com.helix.app.provider.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.provider.catalog.ProviderTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderApiKeyVisibilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun visibilityTogglePreservesInputAndSaveAndReopeningMasksAgain() {
        val form = mutableStateOf(fixture())
        val open = mutableStateOf(true)
        var saved: ProviderForm? = null
        compose.setContent {
            MaterialTheme {
                if (open.value) {
                    ProviderFormDialog(form.value, false, { form.value = it }, { saved = form.value }, {
                        open.value =
                            false
                    })
                }
            }
        }
        key().performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedKey("•".repeat("synthetic-key".length))
        toggle().assertContentDescriptionEquals(label(R.string.provider_show_api_key)).performClick()
        toggle().assertContentDescriptionEquals(label(R.string.provider_hide_api_key))
        assertRenderedKey("synthetic-key")
        key().performTextReplacement("replacement-fixture")
        toggle().performClick()
        key().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedKey("•".repeat("replacement-fixture".length))
        toggle().performClick()
        assertRenderedKey("replacement-fixture")
        compose.onNodeWithTag("provider-form-save").performClick()
        compose.runOnIdle { assertEquals("replacement-fixture", saved!!.fields.apiKey) }
        compose.onNodeWithTag("provider-form-cancel").performClick()
        compose.onNodeWithTag("provider-form-dialog").assertDoesNotExist()
        compose.runOnIdle { open.value = true }
        key().performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle { assertEquals("replacement-fixture", form.value.fields.apiKey) }
    }

    @Test
    fun savedCredentialRevealNeverChangesDraftAndHidingAllowsReplacement() {
        val form = mutableStateOf(storedFixture())
        var reads = 0
        compose.setContent {
            MaterialTheme {
                ProviderApiKeyField(form.value, { form.value = it }) {
                    reads++
                    "saved-synthetic-key"
                }
            }
        }
        compose.runOnIdle { assertEquals(0, reads) }
        toggle().performClick()
        compose.waitForIdle()
        assertRenderedKey("saved-synthetic-key")
        compose.runOnIdle { assertEquals("", form.value.fields.apiKey) }
        toggle().performClick()
        assertRenderedKey("")
        key().performTextReplacement("new-fixture")
        toggle().performClick()
        assertRenderedKey("new-fixture")
        compose.runOnIdle {
            assertEquals(1, reads)
            form.value = storedFixture().copy(providerId = "another-provider")
        }
        key().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedKey("")
    }

    @Test
    fun backgroundHidesSavedKeyAndCancelledReadCannotRestoreIt() {
        val lifecycleOwner =
            object : androidx.lifecycle.LifecycleOwner {
                val registry = androidx.lifecycle.LifecycleRegistry(this)
                override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
            }
        val pending = kotlinx.coroutines.CompletableDeferred<String>()
        compose.runOnIdle { lifecycleOwner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
            ) {
                MaterialTheme {
                    ProviderApiKeyField(storedFixture(), {}) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pending.await() }
                    }
                }
            }
        }
        toggle().performClick()
        compose.runOnIdle {
            lifecycleOwner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
            pending.complete("late-synthetic-key")
        }
        compose.waitForIdle()
        key().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedKey("")
        compose.runOnIdle { lifecycleOwner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        toggle().performClick()
        compose.waitForIdle()
        assertRenderedKey("late-synthetic-key")
        compose.runOnIdle { lifecycleOwner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        key().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedKey("")
    }

    private fun storedFixture() =
        fixture().copy(
            providerId = "existing",
            hasStoredKey = true,
            fields = fixture().fields.copy(apiKey = ""),
        )

    private fun assertRenderedKey(expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        key().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            expected,
            layouts
                .single()
                .layoutInput.text.text,
        )
    }

    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun key() = compose.onNodeWithTag("provider-form-key")

    private fun toggle() = compose.onNodeWithTag("provider-form-key-visibility")

    private fun fixture() =
        ProviderForm(
            null,
            ProviderTemplateCatalog.sglang,
            ProviderForm.FormFields("Server", "https://example.test/v1", "model", "", "", "synthetic-key"),
            false,
            null,
        )
}
