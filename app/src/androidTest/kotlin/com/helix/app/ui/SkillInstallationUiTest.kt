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
import com.helix.app.skills.SkillInstallationSection
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class SkillInstallationUiTest {
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
            val owner = androidx.activity.compose.LocalActivityResultRegistryOwner.current
            CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides requireNotNull(owner),
                LocalContext provides context,
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Column(
                        Modifier.width(320.dp).verticalScroll(rememberScrollState()),
                    ) { SkillInstallationSection(service, requireNotNull(app.appContainer.skillInstallationService)) }
                }
            }
        }
        try {
            val path = service.saveDraft(name, "Use for this test", "Read the input.")
            compose.onNodeWithTag("skill-installer-path").performScrollTo().performTextInput(path)
            compose.onNodeWithTag("skill-installer-preview").performScrollTo().performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("skill-installer-install").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithTag("skill-installer-install")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("skill-installer-result").fetchSemanticsNodes().isNotEmpty()
            }
            val key =
                app.appContainer.skillRepository
                    .list()
                    .single { it.key.name == name }
                    .key
            org.junit.Assert.assertFalse(requireNotNull(app.appContainer.skillInstallationService).isEnabled(key))
            compose.onNodeWithTag("skill-installer-enable").performScrollTo().performClick()
            compose.waitUntil(10000) { requireNotNull(app.appContainer.skillInstallationService).isEnabled(key) }
        } finally {
            app.appContainer.skillRepository.list().filter { it.key.name == name }.forEach {
                app.appContainer.skillRepository.removePermanentlyForPrivacy(it.key)
            }
            val root = app.filesDir.toPath().resolve("workspaces/app/work/skills/$name")
            if (Files.exists(root)) {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}
