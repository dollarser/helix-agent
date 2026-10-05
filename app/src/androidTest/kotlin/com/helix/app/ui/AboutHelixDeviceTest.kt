package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutHelixDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun developerLinksOpenOnlyOnUserActionAndBrowserFailureIsVisible() {
        val opened = mutableListOf<AboutHelixLink>()
        compose.setContent {
            MaterialTheme {
                AboutHelixContent("fixture") { link ->
                    opened += link
                    link != AboutHelixLink.PROJECT
                }
            }
        }
        compose.runOnIdle { assertEquals(emptyList<AboutHelixLink>(), opened) }
        compose.onNodeWithTag("about-project").performClick()
        compose.onNodeWithTag("about-link-error").assertIsDisplayed()
        for (link in AboutHelixLink.entries.filter { it != AboutHelixLink.PROJECT }) {
            compose.onNodeWithTag(link.tag).performClick()
            compose.onNodeWithTag("about-link-error").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(AboutHelixLink.entries.toSet(), opened.toSet()) }
    }

    @Test fun settingsHasAnAboutEntryWithoutOpeningLinksAutomatically() {
        compose.setContent { MaterialTheme { SettingsScreen() } }
        AboutHelixLink.entries.forEach { link ->
            compose.onNodeWithTag(link.tag).performScrollTo().assertIsDisplayed()
        }
    }
}
