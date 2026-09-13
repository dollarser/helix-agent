package com.helix.app.ui

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import com.helix.app.allfiles.AllFilesModule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SystemPermissionsNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bothChannelsExposePermissionActionsWithoutRequestingOnEntry() {
        compose.resetDeterministicUiState()
        val permission = Manifest.permission.WRITE_CALENDAR
        val before = compose.activity.checkSelfPermission(permission)
        compose.navigateTo("permissions")
        listOf(
            "permission-notifications",
            "permission-calendar",
            "permission-listener",
            "permission-app-settings",
        ).forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        assertEquals(before, compose.activity.checkSelfPermission(permission))
        if (AllFilesModule.AVAILABLE) {
            compose.onNodeWithTag("permission-files").performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithTag("permission-files").assertDoesNotExist()
        }
    }

    @Test
    fun missingProviderOffersSettingsNavigation() {
        compose.resetDeterministicUiState()
        deleteEditableProviders(compose.container())
        compose.waitUntil {
            compose
                .container()
                .providerService.rows.value
                .none { it.chatSelectable }
        }
        val chat = compose.container().chatService
        chat.closeSession()
        compose.waitUntil { chat.screen.value.openSessionId == null }
        compose.onNodeWithTag("chat-setup-provider").performScrollTo().performClick()
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()
    }
}
