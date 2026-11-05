package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.R
import com.helix.extensions.skills.BuiltInSkills
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StandaloneSkillCatalogDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val rows =
        BuiltInSkills.documents().map { doc ->
            SkillListItem(
                SkillKey(doc.catalogEntry.source, doc.catalogEntry.name, doc.catalogEntry.contentHash),
                doc.catalogEntry.description,
                true,
            )
        }

    @Test fun builtInsAreGroupedAndDetailsDoNotAddEmptyConfiguration() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StandaloneSkillCatalog(rows, {})
                }
            }
        }
        compose.onNodeWithTag("skill-card-BUILT_IN-helix-docs").assertDoesNotExist()
        compose.onNodeWithTag("builtin-skills-group").performClick()
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.skill_catalog_helix_docs)).assertIsDisplayed()
        compose.onNodeWithText("helix-docs").assertDoesNotExist()
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").performClick()
        compose
            .onNodeWithText(context.getString(R.string.standalone_skill_identifier, "helix-docs"))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plugin_configuration)).assertDoesNotExist()
        compose.onNodeWithTag("builtin-skills-group").performScrollTo().performClick()
        compose.onNodeWithTag("skill-card-BUILT_IN-helix-docs").assertDoesNotExist()
    }

    @Test fun searchMatchesLocalizedNamesAndStableIdentifiersAndClearsBackToGroup() {
        val query = mutableStateOf(context.getString(R.string.skill_catalog_helix_docs))
        compose.setContent { MaterialTheme { StandaloneSkillCatalog(rows, {}, query.value) } }
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").assertIsDisplayed()
        compose.onNodeWithTag("skill-header-BUILT_IN-mcp-installer").assertDoesNotExist()
        compose.runOnIdle { query.value = "mcp-installer" }
        compose.onNodeWithTag("skill-header-BUILT_IN-mcp-installer").assertIsDisplayed()
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").assertDoesNotExist()
        compose.runOnIdle { query.value = "no-such-skill-1783" }
        compose.onNodeWithTag("builtin-skills-group").assertDoesNotExist()
        compose.runOnIdle { query.value = "" }
        compose.onNodeWithTag("builtin-skills-group").assertIsDisplayed()
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").assertDoesNotExist()
    }

    @Test fun importedNamesDoNotSpoofBuiltInLabelsAndUseDoesNotMutateRows() {
        val imported =
            rows
                .first { it.key.name == "helix-docs" }
                .copy(
                    key = SkillKey(SkillSource.USER_IMPORTED, "helix-docs", "import-hash"),
                    description = "Imported workflow",
                    enabled = false,
                )
        var uses = 0
        compose.setContent { MaterialTheme { StandaloneSkillCatalog(listOf(imported), { uses++ }) } }
        compose.onNodeWithText("helix-docs").assertIsDisplayed()
        compose.onNodeWithText("Imported workflow").assertIsDisplayed()
        compose.onNodeWithTag("builtin-skills-group").assertDoesNotExist()
        compose.onNodeWithTag("skill-use-USER_IMPORTED-helix-docs").performClick()
        compose.runOnIdle {
            assertEquals(1, uses)
            assertEquals(false, imported.enabled)
        }
    }

    @Test fun replacingSnapshotResetsDetailsAndEmptyCatalogAddsNoGroup() {
        val current = mutableStateOf(listOf(rows.first()))
        compose.setContent { MaterialTheme { StandaloneSkillCatalog(current.value, {}, "helix-docs") } }
        compose.runOnIdle { current.value = listOf(rows.first { it.key.name == "helix-docs" }) }
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").performClick()
        compose
            .onNodeWithText(
                context.getString(R.string.standalone_skill_identifier, "helix-docs"),
            ).assertIsDisplayed()
        compose.runOnIdle {
            current.value =
                current.value.map {
                    it.copy(
                        key = it.key.copy(snapshotHash = "new-hash"),
                    )
                }
        }
        compose
            .onNodeWithText(
                context.getString(R.string.standalone_skill_identifier, "helix-docs"),
            ).assertDoesNotExist()
        compose.runOnIdle { current.value = emptyList() }
        compose.onNodeWithTag("builtin-skills-group").assertDoesNotExist()
    }
}
