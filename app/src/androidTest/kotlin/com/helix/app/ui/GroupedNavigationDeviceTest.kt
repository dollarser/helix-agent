package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.app.ShellDestination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GroupedNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentConversationOpensWithOneClickFromSettings() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                GroupedNavigation(
                    destinations = listOf(ShellDestination.Settings),
                    currentRoute = "settings",
                    conversation = ConversationDrawerState("current", "My current task"),
                    onCurrentConversation = { opened++ },
                    onNewConversation = {},
                    onAllConversations = {},
                    onNavigate = {},
                )
            }
        }
        compose.onNodeWithTag("drawer-current-conversation").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun settingsHeaderOnlyExpandsAndNestedPageSelectsOneChild() {
        val route = mutableStateOf("sessions")
        val visited = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                GroupedNavigation(
                    destinations = listOf(ShellDestination.Settings),
                    currentRoute = route.value,
                    conversation = ConversationDrawerState(null, ""),
                    onCurrentConversation = {},
                    onNewConversation = {},
                    onAllConversations = {},
                ) { selected ->
                    visited += selected
                    route.value = "$selected/system"
                }
            }
        }
        compose.onNodeWithTag("navigation-settings").assertDoesNotExist()
        compose.onNodeWithTag("navigation-group-settings").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), visited) }
        settingsDrawerEntries.forEach { entry ->
            compose.onNodeWithTag("navigation-${entry.route}").performScrollTo().assertIsDisplayed()
        }
        compose
            .onNodeWithTag("navigation-settings/permissions")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithTag("navigation-settings").assertIsNotSelected()
        compose.onNodeWithTag("navigation-group-settings").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-settings/permissions").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(SETTINGS_PERMISSIONS_ROUTE), visited) }
    }

    @Test
    fun singleWorkChildStillRequiresExpandingItsSection() {
        val visited = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                GroupedNavigation(
                    destinations = listOf(ShellDestination.Browser),
                    currentRoute = "sessions",
                    conversation = ConversationDrawerState(null, ""),
                    onCurrentConversation = {},
                    onNewConversation = {},
                    onAllConversations = {},
                ) { visited += it }
            }
        }
        compose.onNodeWithTag("navigation-browser").assertDoesNotExist()
        compose.onNodeWithTag("navigation-group-work").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), visited) }
        compose.onNodeWithTag("navigation-browser").performClick()
        compose.runOnIdle { assertEquals(listOf("browser"), visited) }
    }

    @Suppress("LongMethod")
    @Test
    fun everyDestinationRemainsReachableOnAShortLargeFontWindow() {
        val selected = mutableStateOf(ShellDestination.Sessions.route)
        val visited = mutableListOf<String>()
        var currentOpened = 0
        var created = 0
        var allOpened = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp).height(320.dp)) {
                        GroupedNavigation(
                            destinations = ShellDestination.entries,
                            footer = {
                                androidx.compose.material3.Text("Profile", Modifier.testTag("fixed-profile"))
                            },
                            currentRoute = selected.value,
                            conversation =
                                ConversationDrawerState(
                                    currentSessionId = "current",
                                    currentTitle = "Current draft",
                                ),
                            onCurrentConversation = { currentOpened++ },
                            onNewConversation = { created++ },
                            onAllConversations = { allOpened++ },
                        ) {
                            selected.value = it
                            visited += it
                        }
                    }
                }
            }
        }
        compose
            .onNodeWithTag("drawer-new-conversation")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("drawer-search-conversations").assertDoesNotExist()
        compose
            .onNodeWithTag("drawer-current-conversation")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("drawer-recent").assertDoesNotExist()
        compose.onNodeWithTag("drawer-recent-recent-1").assertDoesNotExist()
        val modelsTop =
            compose
                .onNodeWithTag("navigation-${ShellDestination.Models.route}")
                .fetchSemanticsNode()
                .positionInRoot.y
        val workTop =
            compose
                .onNodeWithTag("navigation-group-work")
                .fetchSemanticsNode()
                .positionInRoot.y
        org.junit.Assert.assertTrue(modelsTop < workTop)
        compose
            .onNodeWithTag("drawer-all-conversations")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        val drawerDestinations =
            ShellDestination.entries.filter {
                it != ShellDestination.Sessions && it != ShellDestination.Git && it != ShellDestination.Setup
            }
        drawerDestinations.forEach { destination ->
            compose.onNodeWithTag("fixed-profile").assertIsDisplayed()
            val groupTag =
                when (destination) {
                    ShellDestination.Tasks, ShellDestination.Artifacts, ShellDestination.Git,
                    ShellDestination.Files, ShellDestination.Browser,
                    -> "navigation-group-work"

                    ShellDestination.Terminal -> null

                    ShellDestination.Extensions, ShellDestination.Setup -> null

                    ShellDestination.Settings -> "navigation-group-settings"

                    else -> null
                }
            if (groupTag != null &&
                compose.onAllNodesWithTag("navigation-${destination.route}").fetchSemanticsNodes().isEmpty()
            ) {
                compose.onNodeWithTag(groupTag).performScrollTo().performClick()
            }
            compose
                .onNodeWithTag(
                    "navigation-${destination.route}",
                ).performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("navigation-${destination.route}").assertIsSelected()
        }
        compose.runOnIdle {
            assertEquals(drawerDestinations.map { it.route }, visited)
            assertEquals(1, currentOpened)
            assertEquals(1, created)
            assertEquals(1, allOpened)
        }
        compose.onNodeWithTag("navigation-sessions").assertDoesNotExist()
        compose.onNodeWithTag("navigation-git").assertDoesNotExist()
        compose.onNodeWithTag("navigation-setup").assertDoesNotExist()
        compose.onNodeWithTag("navigation-group-configure").assertDoesNotExist()
        listOf("capabilities", "readiness", "permissions", "audit").forEach { legacyRoute ->
            compose.onNodeWithTag("navigation-$legacyRoute").assertDoesNotExist()
        }
    }
}
