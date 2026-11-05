package com.helix.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Actual production sheets; only an owned empty session is created and no selection is applied. */
class ConversationMenusLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun emptyContextAndCompactMenusKeepTheirExistingActions() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-add").fetchSemanticsNodes().isNotEmpty()
        }
        val c = compose.container()
        val previous = c.chatService.screen.value.openSessionId
        val id = "menu-layout-${UUID.randomUUID()}"
        c.storage.sessions.create(id, "Menu layout validation", null, null, 0)
        try {
            c.chatService.openSession(id)
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                c.chatService.screen.value.openSessionId == id &&
                    c.chatService.screen.value.contextUsage.usedTokens == 0L
            }
            compose.onNodeWithTag("chat-context-window").assertIsDisplayed()
            captureChatLayout(compose.activity, "menus-empty-context")
            verifyAddAndPluginOrder()
            compose.onNodeWithTag("chat-conversation-details").performClick()
            verifyCompactRow("session-settings-open")
            verifyCompactRow("background-tasks-open")
            captureChatLayout(compose.activity, "menus-management")
            compose.onNodeWithTag("chat-conversation-details-close").performClick()
            assertTrue(
                c.storage.turns
                    .listBySession(id)
                    .isEmpty(),
            )
        } finally {
            if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
            c.storage.deleteSessionPermanently(id)
        }
    }

    private fun verifyAddAndPluginOrder() {
        compose.onNodeWithTag("chat-add").performClick()
        val tags =
            listOf(
                "composer-add-session-group",
                "composer-add-skills",
                "composer-add-connectors",
                "composer-add-expert",
            )
        val bounds = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "Capabilities follow their group in the requested order",
                a.bottom <= b.top,
            )
        }
        verifyCompactRow("composer-add-camera")
        verifyCompactRow("composer-add-skills")
        verifyCompactRow("composer-add-expert")
        captureChatLayout(compose.activity, "menus-add")
        compose.onNodeWithTag("composer-add-connectors").performClick()
        verifyPrimaryPlugins()
    }

    private fun verifyPrimaryPlugins() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("connector-session-bundled-plugin:browser").fetchSemanticsNodes().isNotEmpty() &&
                compose
                    .onAllNodesWithTag(
                        "connector-session-bundled-plugin:mobile-use",
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
        }
        val primary =
            compose
                .onAllNodes(
                    SemanticsMatcher("Plugin selection rows") { node ->
                        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("connector-session-") == true &&
                            node.config.contains(SemanticsProperties.ToggleableState)
                    },
                ).fetchSemanticsNodes()
                .take(2)
                .map { it.config[SemanticsProperties.TestTag] }
        assertEquals(
            listOf("connector-session-bundled-plugin:browser", "connector-session-bundled-plugin:mobile-use"),
            primary,
        )
        val browser = compose.onNodeWithTag("connector-session-bundled-plugin:browser").getUnclippedBoundsInRoot()
        val phone = compose.onNodeWithTag("connector-session-bundled-plugin:mobile-use").getUnclippedBoundsInRoot()
        assertTrue("Browser precedes phone operation", browser.top < phone.top)
        captureChatLayout(compose.activity, "menus-plugins")
        compose.onNodeWithTag("connector-session-close").performClick()
    }

    private fun verifyCompactRow(tag: String) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val singleLine =
            node
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.size == 1
        val height = bounds.bottom - bounds.top
        assertTrue("Retain a usable touch target", height >= 48.dp)
        if (singleLine && compose.activity.resources.configuration.fontScale <= 1.2f) {
            assertTrue("Single-line actions should stay compact", height <= 52.dp)
        }
    }
}
