package com.helix.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.marketplace.MarketplaceSection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class MarketplaceSectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun englishLargeFontSearchAndFilter() = exercise("en")

    @Test
    fun chineseLargeFontSearchAndFilter() = exercise("zh-CN")

    @Suppress("LongMethod")
    private fun exercise(language: String) {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val service = requireNotNull(container.marketplaceService)
        val context =
            app.createConfigurationContext(
                Configuration(app.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                },
            )

        var configureClicked = false

        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(
                        Modifier.width(320.dp).verticalScroll(rememberScrollState()),
                    ) {
                        MarketplaceSection(
                            service = service,
                            onConfigureRequested = { configureClicked = true },
                        )
                    }
                }
            }
        }

        // Section header and search bar
        compose.onNodeWithTag("marketplace-section").assertIsDisplayed()
        compose.onNodeWithTag("marketplace-search-input").assertIsDisplayed()

        // Filter chips exist and are visible
        compose.onNodeWithTag("marketplace-filter-all").assertIsDisplayed()
        compose.onNodeWithTag("marketplace-filter-connector").assertIsDisplayed()
        compose.onNodeWithTag("marketplace-filter-mcp").assertIsDisplayed()
        compose.onNodeWithTag("marketplace-filter-skill").assertIsDisplayed()

        // Filter by Skill: only skills are visible
        compose.onNodeWithTag("marketplace-filter-skill").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("marketplace-item-code-review").assertIsDisplayed()

        // Switch back to All
        compose.onNodeWithTag("marketplace-filter-all").performClick()
        compose.waitForIdle()

        // Search for specific item
        compose.onNodeWithTag("marketplace-search-input").performTextInput("gitlab")
        compose.waitForIdle()
        compose.onNodeWithTag("marketplace-item-gitlab").assertIsDisplayed()

        // Clear search
        compose.onNodeWithTag("marketplace-search-clear", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        // Expand item details
        compose.onNodeWithTag("marketplace-expand-cloudflare-docs").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("marketplace-install-cloudflare-docs").assertIsDisplayed()
    }
}
