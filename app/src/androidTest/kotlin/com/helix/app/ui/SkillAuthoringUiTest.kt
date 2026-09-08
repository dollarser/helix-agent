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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.skills.SkillAuthoringSection
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class SkillAuthoringUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishLargeFontCreation() = exercise("en")

    @Test fun chineseLargeFontCreation() = exercise("zh-CN")

    @Suppress("LongMethod", "NestedBlockDepth") // Real Compose actions enclosed by fixture cleanup.
    private fun exercise(language: String) {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val service = requireNotNull(app.appContainer.skillAuthoringService)
        val context =
            app.createConfigurationContext(
                Configuration(app.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                },
            )
        val name = "ui-skill-${System.currentTimeMillis()}"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(
                        Modifier.width(320.dp).verticalScroll(rememberScrollState()),
                    ) { SkillAuthoringSection(service) }
                }
            }
        }
        try {
            compose.onNodeWithTag("skill-creator-open").performClick()
            compose.onNodeWithTag("skill-creator-name").performScrollTo().performTextInput(name)
            compose.onNodeWithTag("skill-creator-description").performScrollTo().performTextInput("Use for a test")
            compose
                .onNodeWithTag(
                    "skill-creator-body",
                ).performScrollTo()
                .performTextInput("Read and summarize the input.")
            compose.onNodeWithTag("skill-creator-save").performScrollTo().performClick()
            val path = "scope:app:work/skills/$name"
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag(
                        "skill-creator-result",
                    ).fetchSemanticsNodes()
                    .any { it.config.toString().contains(path) }
            }
            compose
                .onNodeWithTag("skill-creator-preview")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            val hash = service.preview(path).snapshotHash
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag(
                        "skill-creator-result",
                    ).fetchSemanticsNodes()
                    .any { it.config.toString().contains(hash) }
            }
            compose.onNodeWithText(hash, substring = true).performScrollTo().assertIsDisplayed()
        } finally {
            val root = app.filesDir.toPath().resolve("workspaces/app/work/skills/$name")
            if (Files.exists(root)) {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}
