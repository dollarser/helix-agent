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
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.UserSkillActivation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class SkillInvocationPickerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkboxesStageMultipleSkillsAndCancelDoesNotChangeDraftOrEnablement() {
        val repository = repository()
        val visible = mutableStateOf(true)
        var applied: List<UserSkillActivation>? = null
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    SkillInvocationPanel(repository, "session", emptyList(), SkillUseContext(), {
                        applied = it
                        true
                    }, { visible.value = false })
                }
            }
        }
        awaitPicker()
        compose.onNodeWithTag("skill-invocation-arguments").assertDoesNotExist()
        compose.onNodeWithTag("skill-session-enable").assertDoesNotExist()
        compose
            .onNodeWithTag("skill-select-first")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        compose
            .onNodeWithTag("skill-select-second")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        assertEquals(null, applied)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.runOnIdle { visible.value = true }
        awaitPicker()
        compose.onNodeWithTag("skill-select-first").assertIsOff()
        compose.onNodeWithTag("skill-select-first").performScrollTo().performClick()
        compose.onNodeWithTag("skill-select-second").performScrollTo().performClick()
        compose.onNodeWithTag("skill-selection-confirm").performClick()
        compose.waitUntil(10_000) { applied != null }
        assertEquals(listOf("first", "second"), applied!!.map { it.key.name })
        assertEquals(listOf("", ""), applied!!.map { it.arguments })
        assertEquals(true, repository.listSelectable("session").all { it.enabled })
        assertEquals(true, repository.listSelectable("other").all { it.enabled })
    }

    @Test fun disabledAndNonUserInvocableSkillsCannotBeSelectedAndStaleApplyFailsAtomically() {
        val repository = repository("user-invocable: false\n")
        val first = repository.listSelectable().first { it.key.name == "first" }.key
        var calls = 0
        compose.setContent {
            MaterialTheme {
                SkillInvocationPanel(repository, "session", emptyList(), SkillUseContext(), {
                    calls++
                    true
                }, {})
            }
        }
        awaitPicker()
        compose.onNodeWithTag("skill-select-second").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("skill-select-first").performScrollTo().performClick()
        repository.setEnabled(first, false, SkillEnablementScope.SESSION, "session")
        compose.onNodeWithTag("skill-selection-confirm").performClick()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.waitUntil(10_000) {
            compose
                .onNodeWithText(context.getString(com.helix.app.R.string.skill_support_apply_failed))
                .runCatching { fetchSemanticsNode() }
                .isSuccess
        }
        assertEquals(0, calls)
        assertEquals(false, repository.listSelectable("session").first { it.key == first }.enabled)
    }

    @Test fun reopeningAllowsRemovingExistingSelection() {
        val repository = repository()
        val first = repository.listSelectable().first().key
        var applied: List<UserSkillActivation>? = null
        compose.setContent {
            MaterialTheme {
                SkillInvocationPanel(repository, "session", listOf(first), SkillUseContext(), {
                    applied = it
                    true
                }, {})
            }
        }
        awaitPicker()
        compose.onNodeWithTag("skill-select-${first.name}").assertIsOn().performClick()
        compose.onNodeWithTag("skill-selection-confirm").performClick()
        compose.waitUntil(10_000) { applied != null }
        assertEquals(emptyList<UserSkillActivation>(), applied)
    }

    private fun awaitPicker() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("skill-invocation-picker").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun repository(secondMetadata: String = ""): SkillRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "invocation-picker")
        val loader = SkillLoader()
        val documents =
            listOf("first", "second").map { name ->
                loader.loadBuiltIn(
                    "---\nname: $name\ndescription: $name\n${if (name == "second") secondMetadata else ""}---\nGuide",
                    name,
                )
            }
        return SkillRepository(root.resolve("s"), root.resolve("state"), root.resolve("trash"), documents)
    }
}
