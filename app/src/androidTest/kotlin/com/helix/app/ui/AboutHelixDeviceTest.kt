package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutHelixDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun developerLinksOpenOnlyOnUserActionAndBrowserFailureIsVisible() {
        var project = 0
        var developer = 0
        compose.setContent {
            MaterialTheme {
                AboutHelixDialog("fixture", {}, {
                    project++
                    false
                }, {
                    developer++
                    true
                })
            }
        }
        compose.runOnIdle { assertEquals(0, project + developer) }
        compose.onNodeWithTag("about-project").performClick()
        compose.onNodeWithTag("about-link-error").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, project) }
        compose.onNodeWithTag("about-developer").performClick()
        compose.onNodeWithTag("about-link-error").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, developer) }
    }

    @Test fun settingsHasAnAboutEntryWithoutOpeningLinksAutomatically() {
        compose.setContent { MaterialTheme { SettingsScreen() } }
        compose.onNodeWithTag("settings-about").performClick()
        compose.onNodeWithTag("about-project").assertIsDisplayed()
        compose.onNodeWithTag("about-developer").assertIsDisplayed()
    }
}
