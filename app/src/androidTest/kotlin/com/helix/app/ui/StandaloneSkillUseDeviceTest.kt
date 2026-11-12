package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class StandaloneSkillUseDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun useFocusesClickedSkillAndDismissalDoesNotSelectOrChangeDefaults() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val session = "skill-catalog-use-${UUID.randomUUID()}"
        container.storage.sessions.create(session, "Skill catalog use", null, null, 0)
        val global = container.pluginService.standaloneSkills()
        val before = container.pluginService.standaloneSkills(session)
        try {
            compose.setContent {
                MaterialTheme {
                    ExtensionsScreen(
                        container.skillAuthoringService,
                        container.skillInstallationService,
                        container.pluginService,
                        skillRepository = container.skillRepository,
                        prepareSession = { it },
                        currentSession = { session },
                    )
                }
            }
            compose.onNodeWithTag("extensions-search").performScrollTo().performTextInput("helix-docs")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("skill-use-BUILT_IN-helix-docs").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("skill-use-BUILT_IN-helix-docs").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("skill-session-enable").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("session-skills-panel").assertDoesNotExist()
            compose.onNodeWithTag("skill-session-enable").performScrollTo().assertIsDisplayed()
            compose
                .onNode(hasText(app.getString(R.string.skill_catalog_helix_docs)) and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.skill_support_close)).performClick()
            compose.onNodeWithTag("skill-use-review").assertDoesNotExist()
            assertEquals(before, container.pluginService.standaloneSkills(session))
            assertEquals(global, container.pluginService.standaloneSkills())
        } finally {
            container.storage.deleteSessionPermanently(session)
        }
    }
}
