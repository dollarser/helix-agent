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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.connector.ConnectorSection
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ConnectorInstallationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishLargeFontCreation() = exercise("en")

    @Test fun chineseLargeFontCreation() = exercise("zh-CN")

    @Suppress("LongMethod", "NestedBlockDepth") // Real Compose actions enclosed by fixture cleanup.
    private fun exercise(language: String) {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val service = app.appContainer.connectorService
        val context =
            app.createConfigurationContext(
                Configuration(app.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                },
            )
        val name = "ui-skill-${System.currentTimeMillis()}"
        compose.setContent {
            val density = LocalDensity.current
            val owner = androidx.activity.compose.LocalActivityResultRegistryOwner.current
            CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides requireNotNull(owner),
                LocalContext provides context,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(
                        Modifier.width(320.dp).verticalScroll(rememberScrollState()),
                    ) { ConnectorSection(service) }
                }
            }
        }
        try {
            val json = """{"docs":{"url":"https://example.test/$name"}}"""
            compose.onNodeWithTag("connector-paste").performScrollTo().performClick()
            compose.onNodeWithTag("connector-json").performScrollTo().performTextInput(json)
            compose.onNodeWithTag("connector-json-preview").performScrollTo().performClick()
            compose.waitUntil(
                10000,
            ) { compose.onAllNodesWithTag("connector-install").fetchSemanticsNodes().isNotEmpty() }
            compose
                .onNodeWithTag("connector-install")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            val hash = service.previewJson(json).contentHash
            compose.waitUntil(10000) { service.list().any { it.hash == hash } }
            val record = service.list().single { it.hash == hash }
            org.junit.Assert.assertFalse(service.enabled(record.endpoints.single()))
        } finally {
            service
                .list()
                .filter { it.endpoints.any { endpoint -> name in endpoint.endpoint.url } }
                .forEach { service.remove(it) }
        }
    }
}
