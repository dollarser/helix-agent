package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class SkillSettingsSummaryDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun summaryCountsOnlyIndependentSelectionAndRefreshesAfterEditorCloses() {
        val repository = repository()
        val key = repository.listSelectable("session").single().key
        val editorOpen = mutableStateOf(false)
        compose.setContent {
            val (summary, failed) =
                rememberSessionSettingsSummary(
                    "session",
                    true,
                    editorOpen.value,
                    app.appContainer.sessionPermissionEdit,
                    repository,
                )
            Text("selected=${summary?.skills};failed=$failed")
        }
        awaitText("selected=1;failed=false")
        assertEquals(2, repository.list("session").count { it.enabled })
        compose.runOnIdle { editorOpen.value = true }
        compose.waitForIdle()
        repository.setEnabled(key, false, SkillEnablementScope.SESSION, "session")
        compose.runOnIdle { editorOpen.value = false }
        awaitText("selected=0;failed=false")
        // Plugin availability of the same skill must not count as an independent choice.
        assertEquals(2, repository.list("session").count { it.enabled })
    }

    @Test fun closingInvocationReviewKeepsSessionChoiceWithoutInvoking() {
        val repository = repository()
        val key = repository.listSelectable("session").single().key
        repository.setEnabled(key, false, SkillEnablementScope.GLOBAL)
        val visible = mutableStateOf(true)
        var invoked = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    SkillUseDialog(
                        repository,
                        "session",
                        key,
                        SkillUseActions(SkillUseContext(), {
                            invoked++
                            true
                        }, { visible.value = false }),
                    )
                }
            }
        }
        awaitSwitch()
        compose.onNodeWithTag("skill-session-enable").assertIsOff().performClick()
        compose.waitUntil(10_000) { repository.listSelectable("session").single().enabled }
        compose.onNodeWithText(app.getString(R.string.skill_support_session_persistence)).assertExists()
        compose.onNodeWithText(app.getString(R.string.skill_invocation_once)).assertExists()
        compose.onNodeWithText(app.getString(R.string.skill_support_close)).performClick()
        compose.onNodeWithTag("skill-use-review").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        awaitSwitch()
        compose.onNodeWithTag("skill-session-enable").assertIsOn().performClick()
        compose.waitUntil(10_000) { !repository.listSelectable("session").single().enabled }
        compose.onNodeWithText(app.getString(R.string.skill_support_close)).performClick()
        assertEquals(0, invoked)
        assertEquals(false, repository.listSelectable("session").single().enabled)
        assertEquals(false, repository.listSelectable("other").single().enabled)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onNodeWithText(text).runCatching { fetchSemanticsNode() }.isSuccess
        }
    }

    private fun awaitSwitch() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("skill-session-enable").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun repository(): SkillRepository {
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "skill-settings-summary")
        val loader = SkillLoader()
        val independent =
            loader.loadBuiltIn(
                "---\nname: independent\ndescription: Independent\n---\nGuide",
                "independent",
            )
        val bundled = loader.loadBuiltIn("---\nname: bundled\ndescription: Bundled\n---\nGuide", "bundled")
        return SkillRepository(
            root.resolve("snapshots"),
            root.resolve("state"),
            root.resolve("trash"),
            listOf(independent),
            pluginAvailable = { key, session -> key.name == "independent" && session == "session" },
            contributedDocuments = { listOf(bundled) },
        )
    }
}
