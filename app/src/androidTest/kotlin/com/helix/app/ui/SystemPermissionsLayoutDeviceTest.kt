package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** No actual system-grant callbacks are invoked; injected destinations exercise navigation only. */
class SystemPermissionsLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fileDestinationsAndOptionalDiagnosticsRemainSeparate() {
        var locationsOpened = 0
        compose.setContent {
            MaterialTheme {
                SystemPermissionsScreen(
                    onFileLocations = { locationsOpened++ },
                    filePermissions = { Text("File fixture", Modifier.testTag("file-fixture")) },
                    devicePermissions = { Text("Device fixture", Modifier.testTag("device-fixture")) },
                    advancedPermissions = { Text("Diagnostic fixture", Modifier.testTag("diagnostic-fixture")) },
                )
            }
        }
        compose.runOnIdle { assertEquals(0, locationsOpened) }
        compose.onNodeWithTag("diagnostic-fixture").assertDoesNotExist()
        compose.onNodeWithTag("permission-file-locations").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, locationsOpened) }
        compose.onNodeWithTag("permission-files").performScrollTo().performClick()
        compose.onNodeWithTag("file-fixture").assertIsDisplayed()
        compose.onNodeWithTag("permission-files-back").performClick()
        compose.onNodeWithTag("device-fixture").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("permissions-root-details").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostic-fixture").performScrollTo().assertIsDisplayed()
    }

    @Test fun absentOptionalPermissionsDoNotLeaveEmptyEntries() {
        compose.setContent { MaterialTheme { SystemPermissionsScreen() } }
        compose.onNodeWithTag("permission-notifications").assertIsDisplayed()
        compose.onNodeWithTag("permission-files").assertDoesNotExist()
        compose.onNodeWithTag("permission-file-locations").assertDoesNotExist()
        compose.onNodeWithTag("permissions-root-details").assertDoesNotExist()
        compose.onNodeWithTag("permission-app-list").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("permission-declared-android.permission.INTERNET-status").assertDoesNotExist()
        compose.onNodeWithTag("permissions-declared").performScrollTo().performClick()
        compose
            .onNodeWithTag("permission-declared-android.permission.INTERNET-status")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("permission-app-settings").performScrollTo().assertIsDisplayed()
    }
}
