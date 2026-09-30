package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Curated install is primary; advanced invalid input remains repairable and makes no network request. */
class LocalModelDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun curatedCatalogIsPrimaryAndAdvancedImportRemainsAvailable() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        compose.setContent {
            MaterialTheme {
                LocalModelDialog(
                    providerService = app.appContainer.providerService,
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("local-model-catalog-qwen3-4b-instruct-2507-q4-k-m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("local-model-source-modelscope").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("local-model-source-hugging_face").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("local-model-name").assertDoesNotExist()
        compose.onNodeWithTag("local-model-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("local-model-name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("local-model-url").performScrollTo().assertIsDisplayed()
    }

    @Test fun failedAdvancedDownloadPreservesDraftAndCanBeDismissed() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    LocalModelDialog(
                        providerService = app.appContainer.providerService,
                    ) { visible.value = false }
                }
            }
        }
        compose.onNodeWithTag("local-model-advanced").performScrollTo().performClick()
        for ((field, value) in listOf(
            "name" to "My model",
            "url" to "http://invalid.example/model.gguf",
            "hash" to "a".repeat(64),
            "size" to "100",
        )) {
            compose.onNodeWithTag("local-model-$field").performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithTag("local-model-download").assertIsDisplayed().performClick()
        compose.waitUntil(10000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("local-model-error"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("local-model-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("local-model-name").performScrollTo().assertTextContains("My model")
        compose
            .onNodeWithTag(
                "local-model-url",
            ).performScrollTo()
            .assertTextContains("http://invalid.example/model.gguf")
        compose.onNodeWithTag("local-model-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("local-model-name").assertDoesNotExist()
    }
}
