package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StandaloneSkillLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun productionCatalogGroupsSearchesAndInspectsWithoutChangingSkills() {
        val service = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.pluginService
        val before = service.standaloneSkills()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("open-navigation").fetchSemanticsNodes().isNotEmpty()
        }
        compose.navigateTo("extensions")
        compose.onNodeWithTag("extensions-tab-manage").performClick()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("builtin-skills-group").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("builtin-skills-group").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").assertDoesNotExist()
        captureChatLayout(compose.activity, "skill-catalog-grouped")
        compose
            .onNodeWithTag(
                "extensions-search",
            ).performScrollTo()
            .performTextInput(compose.activity.getString(R.string.skill_catalog_helix_docs))
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").performScrollTo().performClick()
        compose
            .onNodeWithText(compose.activity.getString(R.string.standalone_skill_identifier, "helix-docs"))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("plugin-catalog-empty").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.plugins_no_matches)).assertDoesNotExist()
        captureChatLayout(compose.activity, "skill-catalog-details")
        compose
            .onNodeWithTag(
                "extensions-search",
            ).performScrollTo()
            .performTextClearance()
        compose.onNodeWithTag("extensions-search").performTextInput("no-such-catalog-1783")
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("plugin-catalog-empty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plugin-catalog-empty").assertIsDisplayed()
        compose
            .onNodeWithTag(
                "extensions-search",
            ).performScrollTo()
            .performTextClearance()
        compose.onNodeWithTag("extensions-search").performTextInput("another-missing-catalog-1783")
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("plugin-catalog-empty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plugin-catalog-empty").assertIsDisplayed()

        compose.onNodeWithTag("builtin-skills-group").assertDoesNotExist()
        compose.onNodeWithTag("extensions-search").performScrollTo().performTextClearance()
        compose.onNodeWithTag("skill-header-BUILT_IN-helix-docs").assertDoesNotExist()
        assertEquals(before, service.standaloneSkills())
    }

    @Test fun onceFromCatalogReturnsToComposerWithoutSendingOrReplacingInput() =
        kotlinx.coroutines.runBlocking {
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val previous = c.chatService.screen.value.openSessionId
            val session = "skill-layout-${java.util.UUID.randomUUID()}"
            c.storage.sessions.create(session, "Skill handoff layout", null, null, 0)
            try {
                c.chatService.openSession(session)
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    c.chatService.screen.value.openSessionId == session &&
                        compose.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                compose.onNodeWithTag("chat-input").performTextInput("保留这条草稿")
                compose.navigateTo("extensions")
                compose.onNodeWithTag("extensions-tab-manage").performClick()
                compose.onNodeWithTag("extensions-search").performScrollTo().performTextInput("helix-docs")
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    compose.onAllNodesWithTag("skill-use-BUILT_IN-helix-docs").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("skill-use-BUILT_IN-helix-docs").performScrollTo().performClick()
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    compose.onAllNodesWithTag("skill-invocation-confirm").fetchSemanticsNodes().isNotEmpty()
                }
                captureChatLayout(compose.activity, "skill-use-focused")
                compose.onNodeWithTag("skill-invocation-arguments").assertDoesNotExist()
                compose.onNodeWithTag("skill-invocation-confirm").performClick()
                compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                    compose.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty()
                }
                val skillTitle = compose.activity.getString(R.string.skill_catalog_helix_docs)
                compose.onNodeWithTag("chat-input").assertTextEquals(" $skillTitle 保留这条草稿")
                compose.onNodeWithTag("chat-input").assertTextContains(
                    compose.activity.getString(R.string.skill_catalog_helix_docs),
                    substring = true,
                )
                compose.onNodeWithTag("skill-activation-helix-docs").assertDoesNotExist()
                compose.onNodeWithTag("skill-use-review").assertDoesNotExist()
                compose.onNodeWithTag("skill-unapplied").assertDoesNotExist()
                val draft = requireNotNull(c.chatService.loadComposerDraft(session))
                assertEquals("保留这条草稿", draft.text)
                assertEquals("helix-docs", draft.skillActivations.single().name)
                assertEquals("", draft.skillActivations.single().arguments)
                assertTrue(
                    c.storage.turns
                        .listBySession(session)
                        .isEmpty(),
                )
                captureChatLayout(compose.activity, "skill-use-staged")
            } finally {
                if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
                c.storage.deleteSessionPermanently(session)
            }
        }
}
