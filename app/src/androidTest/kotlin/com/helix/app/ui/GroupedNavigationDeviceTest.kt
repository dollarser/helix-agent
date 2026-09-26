package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.app.ShellDestination
import com.helix.app.chat.SessionRowUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GroupedNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("LongMethod")
    @Test
    fun everyDestinationRemainsReachableOnAShortLargeFontWindow() {
        val selected = mutableStateOf(ShellDestination.Sessions.route)
        val visited = mutableListOf<ShellDestination>()
        var currentOpened = 0
        var created = 0
        var searched = 0
        var allOpened = 0
        val recentOpened = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp).height(320.dp)) {
                        GroupedNavigation(
                            destinations = ShellDestination.entries,
                            currentRoute = selected.value,
                            conversation =
                                ConversationDrawerState(
                                    currentSessionId = "current",
                                    currentTitle = "Current draft",
                                    recent =
                                        listOf(
                                            SessionRowUi("recent-1", "Recent one", 1L, false, null, null),
                                        ),
                                ),
                            onCurrentConversation = { currentOpened++ },
                            onNewConversation = { created++ },
                            onSearchConversations = { searched++ },
                            onAllConversations = { allOpened++ },
                            onOpenConversation = { recentOpened += it },
                        ) {
                            selected.value = it.route
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
        compose
            .onNodeWithTag("drawer-search-conversations")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithTag("drawer-current-conversation")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithTag("drawer-recent-recent-1")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithTag("drawer-all-conversations")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        ShellDestination.entries.filter { it != ShellDestination.Sessions }.forEach { destination ->
            val groupTag =
                when (destination) {
                    ShellDestination.Tasks, ShellDestination.Artifacts, ShellDestination.Git,
                    ShellDestination.Files, ShellDestination.Browser, ShellDestination.Terminal,
                    -> "navigation-group-work"

                    ShellDestination.Models, ShellDestination.Extensions, ShellDestination.Setup,
                    -> "navigation-group-configure"

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
            assertEquals(ShellDestination.entries.filter { it != ShellDestination.Sessions }, visited)
            assertEquals(1, currentOpened)
            assertEquals(1, created)
            assertEquals(1, searched)
            assertEquals(1, allOpened)
            assertEquals(listOf("recent-1"), recentOpened)
        }
        compose.onNodeWithTag("navigation-sessions").assertDoesNotExist()
        listOf("capabilities", "readiness", "permissions", "audit").forEach { legacyRoute ->
            compose.onNodeWithTag("navigation-$legacyRoute").assertDoesNotExist()
        }
    }
}
