package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.chat.SessionModelSelectionResult
import com.helix.provider.api.ModelPurpose
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in catalog-only request through the existing account; picker saves only local test state. */
class BuiltInLiveCatalogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun officialOutputModalitiesDriveTheRealPickerWithoutApiManagement() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("helix.liveCatalog") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            check(app.packageName == "com.helix.agent.developer")
            app.account.restore()
            check(app.account.signedIn.value)
            val service = app.awaitAppContainer().providerService
            service.builtInModels.refresh(true)
            val catalog = service.builtInModels.current()
            assertEquals(null, catalog.failure)
            assertTrue(catalog.purposes.values.any { ModelPurpose.EMBEDDING in it })
            val row =
                withTimeout(10_000) {
                    service.rows
                        .first { rows -> rows.any { it.bundled && it.builtInCatalog == catalog } }
                        .single { it.bundled }
                }
            val text = catalog.models.filter { ModelPurpose.CHAT in catalog.purposes[it].orEmpty() }
            val other = catalog.models - text.toSet()
            assertTrue(text.isNotEmpty())
            assertEquals(text, row.conversationModels)
            var selected: String? = null
            compose.setContent {
                MaterialTheme {
                    ComposerModelMenu(
                        listOf(row),
                        null,
                        null,
                        true,
                        { _, model, _ ->
                            selected = model
                            SessionModelSelectionResult.APPLIED
                        },
                        accountEntry = {},
                    )
                }
            }
            compose.onNodeWithTag("chat-model-menu").performClick()
            for (id in other) {
                compose.onNodeWithTag("chat-model-search").performTextReplacement(id)
                compose.onNodeWithTag("chat-model-${row.id}-$id").assertDoesNotExist()
            }
            compose.onNodeWithTag("chat-model-search").performTextReplacement(text.first())
            compose.onNodeWithTag("chat-model-${row.id}-${text.first()}").assertIsDisplayed().performClick()
            compose.onNodeWithTag("chat-model-picker-save").performClick()
            compose.waitUntil { selected == text.first() }
            File(app.cacheDir, "builtin-live-catalog-facts.json").writeText(
                """{"fullCatalog":true,"textModels":${text.size},"nonTextModels":${other.size},"pickerPassed":true}""",
            )
        }
}
