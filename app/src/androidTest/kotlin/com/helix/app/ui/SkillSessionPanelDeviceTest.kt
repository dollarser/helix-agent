package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class SkillSessionPanelDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun useOnceBindsParametersWithoutChangingEnablement() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root =
            java.nio.file.Files
                .createTempDirectory(context.cacheDir.toPath(), "skill-invoke-ui")
        val document =
            com.helix.extensions.skills.SkillLoader().loadBuiltIn(
                "---\nname: workflow\ndescription: Workflow\ndisable-model-invocation: true\n---\nGuide",
                "workflow",
            )
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("s"),
                root.resolve("state"),
                root.resolve("trash"),
                listOf(document),
            )
        var invoked: com.helix.extensions.skills.UserSkillActivation? = null
        compose.setContent {
            MaterialTheme {
                SkillSessionPanel(repository, "ui-session", {}, {}, onInvoke = { invoked = it })
            }
        }
        compose.waitUntil(
            10_000,
        ) { compose.onAllNodesWithTag("skill-invoke-workflow").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("skill-invoke-workflow").performScrollTo().performClick()
        compose.onNodeWithTag("skill-invocation-arguments").performTextInput("中文参数")
        compose.onNodeWithTag("skill-invocation-confirm").performClick()
        compose.waitUntil(10_000) { invoked != null }
        assertEquals("中文参数", invoked!!.arguments)
        assertEquals(repository.list().single().key, invoked!!.key)
        assertEquals(true, repository.list().single().enabled)
        assertEquals(0, repository.listForModel("ui-session", "fake").size)
    }

    @Test
    fun sessionSkillOverrideDoesNotChangeGlobalOrAnotherSession() {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val repository = requireNotNull(container.skillRepository)
        val first = repository.list().single { it.key.name == "repo-inspection" }
        val globalEnabled = first.enabled
        val session = "skill-ui-${UUID.randomUUID()}"
        val other = "$session-other"
        container.storage.sessions.create(session, "Skill UI", null, null, 0)
        container.storage.sessions.create(other, "Skill other", null, null, 0)
        try {
            compose.setContent {
                MaterialTheme { SkillSessionPanel(repository, session, {}, {}) }
            }
            val tag = "session-skill-${first.key.name}-${first.key.snapshotHash.take(8)}"
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onNodeWithTag(tag).performScrollTo()
            if (globalEnabled) {
                node.assertIsOn().performClick()
            } else {
                node.assertIsOff().performClick()
            }
            compose.waitUntil(10_000) {
                repository.list(session).single { it.key == first.key }.enabled == !globalEnabled
            }

            assertEquals(globalEnabled, repository.list().single { it.key == first.key }.enabled)
            assertEquals(globalEnabled, repository.list(other).single { it.key == first.key }.enabled)
        } finally {
            container.storage.deleteSessionPermanently(session)
            container.storage.deleteSessionPermanently(other)
        }
    }
}
