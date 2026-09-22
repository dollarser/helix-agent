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

    @Test fun everyDestinationRemainsReachableOnAShortLargeFontWindow() {
        val selected = mutableStateOf(ShellDestination.Sessions.route)
        val visited = mutableListOf<ShellDestination>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(240.dp).height(320.dp)) {
                        GroupedNavigation(ShellDestination.entries, selected.value) {
                            selected.value = it.route
                            visited += it
                        }
                    }
                }
            }
        }
        ShellDestination.entries.forEach { destination ->
            compose
                .onNodeWithTag(
                    "navigation-${destination.route}",
                ).performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.onNodeWithTag("navigation-${destination.route}").assertIsSelected()
        }
        compose.runOnIdle { assertEquals(ShellDestination.entries, visited) }
    }
}
