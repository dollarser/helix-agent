package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class BundledSkillSelectionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sessionSettingsHidePluginSkillsBeforeAndAfterReopening() {
        val repository = repository()
        val session = mutableStateOf("enabled-plugin")
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) SkillSessionPanel(repository, session.value, {}, { visible.value = false })
            }
        }
        awaitPanel()
        assertOnlyIndependentSkill(repository, session.value)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("session-skills-panel").assertDoesNotExist()
        compose.runOnIdle {
            session.value = "disabled-plugin"
            visible.value = true
        }
        awaitPanel()
        assertOnlyIndependentSkill(repository, session.value)
    }

    @Test fun invocationPickerDoesNotOfferPluginSkills() {
        val repository = repository()
        var invoked = 0
        compose.setContent {
            MaterialTheme {
                SkillSessionPanel(repository, "enabled-plugin", {}, {}, onInvoke = {
                    invoked++
                    true
                })
            }
        }
        awaitPanel()
        compose.onNodeWithTag("skill-invoke-independent").assertExists()
        compose.onNodeWithTag("skill-invoke-plugin-workflow").assertDoesNotExist()
        compose.onNodeWithText("Plugin workflow").assertDoesNotExist()
        assertEquals(0, invoked)
    }

    @Test fun staleDirectSkillEntryCannotExposePluginEnablementOrInvocation() {
        val repository = repository()
        val key = repository.list().single { it.key.name == "plugin-workflow" }.key
        var invoked = 0
        compose.setContent {
            MaterialTheme {
                SkillUseDialog(
                    repository,
                    "enabled-plugin",
                    key,
                    SkillUseActions(SkillUseContext(), {
                        invoked++
                        true
                    }, {}),
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("skill-use-failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("skill-session-enable").assertDoesNotExist()
        compose.onNodeWithTag("skill-invocation-confirm").assertDoesNotExist()
        assertEquals(0, invoked)
    }

    @Test fun independentSwitchStaysOffWhenNativePluginProvidesIdenticalContent() {
        val repository = repository(sameContentNative = true)
        val key = repository.list().single { it.key.name == "independent" && it.key.ownerId.isEmpty() }.key
        repository.setEnabled(key, false, SkillEnablementScope.GLOBAL)
        compose.setContent {
            MaterialTheme {
                SkillUseDialog(
                    repository,
                    "enabled-plugin",
                    key,
                    SkillUseActions(SkillUseContext(), { true }, {}),
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("skill-session-enable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("skill-session-enable").assertIsOff()
        compose.onNodeWithTag("skill-invocation-confirm").assertIsNotEnabled()
        assertEquals(false, repository.list("enabled-plugin").single { it.key == key }.enabled)
        assertEquals(false, repository.listSelectable("enabled-plugin").single { it.key == key }.enabled)
        compose.onNodeWithTag("skill-session-enable").performClick()
        compose.waitUntil(10_000) { repository.listSelectable("enabled-plugin").single { it.key == key }.enabled }
        compose.onNodeWithTag("skill-session-enable").assertIsOn().performClick()
        compose.waitUntil(10_000) { !repository.listSelectable("enabled-plugin").single { it.key == key }.enabled }
        compose.onNodeWithTag("skill-session-enable").assertIsOff()
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "enabled-plugin") }
        val owned = repository.list("enabled-plugin").single { it.key.ownerId == "same-content" }.key
        assertEquals("independent", repository.read(owned, "enabled-plugin").catalogEntry.name)
        assertEquals(false, repository.list("disabled-plugin").single { it.key == key }.enabled)
    }

    private fun assertOnlyIndependentSkill(
        repository: SkillRepository,
        session: String,
    ) {
        val rows = repository.list(session)
        val independent = rows.single { it.key.name == "independent" }
        val bundled = rows.single { it.key.name == "plugin-workflow" }
        compose.onNodeWithTag("session-skill-independent-${independent.key.snapshotHash.take(8)}").assertExists()
        compose.onNodeWithTag("session-skill-plugin-workflow-${bundled.key.snapshotHash.take(8)}").assertDoesNotExist()
        compose.onNodeWithText("Plugin workflow").assertDoesNotExist()
    }

    private fun awaitPanel() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("session-skills-panel").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun repository(sameContentNative: Boolean = false): SkillRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "bundled-skill-selection")
        val loader = SkillLoader()
        val independent =
            loader.loadBuiltIn("---\nname: independent\ndescription: Independent workflow\n---\nGuide", "independent")
        val bundled =
            loader.loadBuiltIn(
                "---\nname: plugin-workflow\ndescription: Plugin workflow\n---\nGuide",
                "plugin-workflow",
            )
        return SkillRepository(
            root.resolve("snapshots"),
            root.resolve("state"),
            root.resolve("trash"),
            listOf(independent),
            contributedDocuments = { session ->
                if (session == null || session == "enabled-plugin") {
                    buildList {
                        add(bundled.copy(catalogEntry = bundled.catalogEntry.copy(ownerId = "bundled")))
                        if (sameContentNative) {
                            val scoped =
                                independent.copy(
                                    catalogEntry = independent.catalogEntry.copy(ownerId = "same-content"),
                                )
                            add(scoped)
                        }
                    }
                } else {
                    emptyList()
                }
            },
        )
    }
}
