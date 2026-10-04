package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Rule
import org.junit.Test

class UnifiedExtensionsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun addedIsDefaultAndImportIsOnlyShownAfterAdding() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        compose.setContent {
            MaterialTheme {
                ExtensionsScreen(
                    c.skillAuthoringService,
                    c.skillInstallationService,
                    c.pluginService,
                    c.marketplaceService,
                )
            }
        }
        compose.onNodeWithTag("marketplace-section").assertDoesNotExist()
        compose.onNodeWithTag("connector-import").assertDoesNotExist()
        compose.onNodeWithTag("extensions-add").performScrollTo().performClick()
        compose.onNodeWithTag("connector-import").assertExists()
        compose.onNodeWithTag("extensions-add").performScrollTo().performClick()
        compose.onNodeWithTag("connector-import").assertDoesNotExist()
        compose.onNodeWithTag("extensions-tab-market").performScrollTo().performClick()
        compose.onNodeWithTag("marketplace-section").assertExists()
    }
}
