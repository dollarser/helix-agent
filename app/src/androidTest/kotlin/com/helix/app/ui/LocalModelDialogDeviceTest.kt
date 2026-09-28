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

/** Invalid local input must preserve a repairable draft, without making a network request. */
class LocalModelDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedDownloadPreservesDraftAndCanBeDismissed() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val visible = mutableStateOf(true)
        var changed = false
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    LocalModelDialog(requireNotNull(app.appContainer.providerService.localModels), { changed = true }, {
                        visible.value =
                            false
                    })
                }
            }
        }
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
        assertFalse(changed)
    }
}
