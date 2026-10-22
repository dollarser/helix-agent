package com.helix.app.provider

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.ui.ComposerModelMenu
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure UI fixtures, with no app data writes, network requests or real model calls. */
class BuiltInModelUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val row =
        ProviderRowUi(
            BuiltInModel.PROVIDER_ID,
            "Helix",
            null,
            BuiltInModel.API_ROOT,
            ProviderResidence.PUBLIC_CLOUD,
            BuiltInModel.MODEL_ID,
            false,
            false,
            ConnectionTestStatus.Untested,
            BuiltInModel.capabilities,
            null,
            provisioning = ProviderProvisioningKind.BUNDLED_SERVICE,
        )

    @Test fun noDirectoryHidesBuiltInAndNeverOffersProviderConfiguration() {
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(
                    listOf(row),
                    null,
                    null,
                    true,
                    { _, _, _ -> error("No models discovered") },
                    onConfigureSource = {},
                )
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        compose.onNodeWithTag("chat-model-${row.id}-${BuiltInModel.MODEL_ID}").assertDoesNotExist()
        compose.onNodeWithTag("chat-model-configure-BUNDLED_SERVICE").assertDoesNotExist()
        reveal("chat-model-configure-USER_CONFIGURED")
        compose.onNodeWithTag("chat-model-configure-USER_CONFIGURED").assertIsDisplayed()
        reveal("chat-model-empty")
        compose.onNodeWithTag("chat-model-empty").assertIsDisplayed()
    }

    @Test fun dynamicNewModelIsSelectableWithoutCapabilityOrGenerationEvidence() {
        var selected: Pair<String, String>? = null
        val discovered = row.copy(builtInCatalog = BuiltInModelCatalog(1, listOf("future-model")))
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(discovered), null, null, true, { provider, model, _ ->
                    selected =
                        provider to model
                    com.helix.app.chat.SessionModelSelectionResult.APPLIED
                })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-${row.id}-future-model")
        compose.onNodeWithTag("chat-model-${row.id}-future-model").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
        compose.onNodeWithTag("chat-model-picker-save").performClick()
        compose.runOnIdle { assertEquals(row.id to "future-model", selected) }
    }

    @Test fun failedDiscoveryKeepsCurrentSelectionAndDoesNotSelectAnotherProvider() {
        var selections = 0
        compose.setContent {
            MaterialTheme {
                ComposerModelMenu(listOf(row), row.id, "previous-model", true, { _, _, _ ->
                    selections++
                    com.helix.app.chat.SessionModelSelectionResult.APPLIED
                })
            }
        }
        compose.onNodeWithTag("chat-model-menu").performClick()
        reveal("chat-model-hidden-current")
        compose.onNodeWithTag("chat-model-hidden-current").assertIsDisplayed()
        reveal("chat-model-empty")
        compose.onNodeWithTag("chat-model-empty").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, selections) }
    }

    private fun reveal(tag: String) {
        compose.onNodeWithTag("chat-model-list").performScrollToNode(hasTestTag(tag))
    }
}
